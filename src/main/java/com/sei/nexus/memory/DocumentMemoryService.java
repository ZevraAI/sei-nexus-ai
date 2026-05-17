package com.sei.nexus.memory;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class DocumentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentMemoryService.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "text/html");

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "docx", "txt", "md", "html");

    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024; // 50 MB
    private static final int TIKA_MAX_CHARS = 10_000_000;

    private final MemoryRepository memoryRepository;
    private final AzureOpenAiClient azureOpenAiClient;

    @Value("${nexus.storage.local-path:./data/documents}")
    private String storagePath;

    @Value("${nexus.memory.chunk-target-words:850}")
    private int chunkTargetWords;

    @Value("${nexus.memory.chunk-overlap-words:100}")
    private int chunkOverlapWords;

    @Value("${nexus.memory.retrieval-top-k:6}")
    private int retrievalTopK;

    public DocumentMemoryService(MemoryRepository memoryRepository,
                                  AzureOpenAiClient azureOpenAiClient) {
        this.memoryRepository = memoryRepository;
        this.azureOpenAiClient = azureOpenAiClient;
    }

    // ---------------------------------------------------------------------------
    // Upload
    // ---------------------------------------------------------------------------

    /**
     * Validates, persists, and asynchronously indexes an uploaded document.
     *
     * @return the newly created {@link KnowledgeDocument} record (status = UPLOADED)
     */
    public KnowledgeDocument uploadDocument(MultipartFile file,
                                            String domainKey,
                                            String title,
                                            String tags,
                                            String userEmail) {
        // --- Validation ---
        if (file == null || file.isEmpty()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "File must not be null or empty");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "File exceeds the 50 MB size limit");
        }
        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "upload";
        String extension = extractExtension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file type '" + extension + "'. Allowed: pdf, docx, txt, md, html");
        }
        if (domainKey == null || domainKey.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "domainKey is required");
        }

        // --- Persist to disk ---
        String documentKey = Keys.uniqueKey("doc");
        Path dir = Paths.get(storagePath, domainKey, documentKey);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create storage directory: " + e.getMessage());
        }
        Path filePath = dir.resolve(originalName);
        try {
            file.transferTo(filePath.toFile());
        } catch (IOException e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save file to disk: " + e.getMessage());
        }

        // --- Detect content type ---
        String contentType = detectContentType(filePath);

        // --- Persist metadata ---
        Instant now = Instant.now();
        KnowledgeDocument doc = new KnowledgeDocument(
                documentKey, domainKey, title, originalName,
                filePath.toAbsolutePath().toString(), file.getSize(),
                contentType, tags, "UPLOADED", 0, null,
                userEmail, now, now);
        memoryRepository.saveDocument(doc);

        // --- Kick off async indexing ---
        indexDocument(documentKey);

        return doc;
    }

    // ---------------------------------------------------------------------------
    // Async indexing
    // ---------------------------------------------------------------------------

    /**
     * Extracts text, chunks it, embeds each chunk, and stores everything.
     * Runs on the Spring async executor so the HTTP response is not blocked.
     */
    @Async
    public void indexDocument(String documentKey) {
        KnowledgeDocument doc = memoryRepository.findByKey(documentKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Document not found: " + documentKey));

        // Mark as INDEXING
        memoryRepository.updateDocumentStatus(documentKey, "INDEXING", 0, null);

        try {
            // --- Extract text ---
            File file = new File(doc.filePath());
            if (!file.exists()) {
                throw new IllegalStateException("File not found on disk: " + doc.filePath());
            }

            String rawText = extractText(file, doc.contentType());
            log.info("Extracted {} chars from document {}", rawText.length(), documentKey);

            // --- Chunk ---
            List<String> chunks = chunkText(rawText, documentKey, doc.domainKey(), doc.title());

            // --- Delete any previous chunks (re-index scenario) ---
            memoryRepository.deleteChunks(documentKey);

            // --- Embed and save each chunk ---
            int chunkNo = 0;
            for (String chunkText : chunks) {
                float[] embedding = azureOpenAiClient.embed(chunkText).embedding();
                int tokens = estimateTokenCount(chunkText);
                String chunkKey = Keys.uniqueKey("chunk");
                DocumentChunk chunk = new DocumentChunk(chunkKey, documentKey, chunkNo++,
                        chunkText, embedding, tokens);
                memoryRepository.saveChunk(chunk);
            }

            memoryRepository.updateDocumentStatus(documentKey, "INDEXED", chunkNo, Instant.now());
            log.info("Indexed document {} with {} chunks", documentKey, chunkNo);

        } catch (Exception ex) {
            log.error("Failed to index document {}: {}", documentKey, ex.getMessage(), ex);
            memoryRepository.updateDocumentStatus(documentKey, "FAILED", 0, null);
        }
    }

    // ---------------------------------------------------------------------------
    // Chunking
    // ---------------------------------------------------------------------------

    /**
     * Normalises whitespace, splits into words, and produces overlapping chunks.
     * Each chunk is prefixed with "{domainKey} | {title} | " for retrieval context.
     */
    public List<String> chunkText(String text, String documentKey,
                                   String domainKey, String title) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Normalise: collapse all whitespace sequences to a single space
        String normalised = text.strip().replaceAll("\\s+", " ");
        String[] words = normalised.split(" ");

        String prefix = domainKey + " | " + title + " | ";
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < words.length) {
            int end = Math.min(start + chunkTargetWords, words.length);
            String chunkBody = String.join(" ", java.util.Arrays.copyOfRange(words, start, end));
            chunks.add(prefix + chunkBody);

            if (end >= words.length) break;
            // Advance by (target - overlap) words so consecutive chunks share overlap words
            start += Math.max(1, chunkTargetWords - chunkOverlapWords);
        }

        return chunks;
    }

    // ---------------------------------------------------------------------------
    // Retrieval
    // ---------------------------------------------------------------------------

    /**
     * Embeds {@code question} and returns the top-K most similar chunks across
     * the specified domains.
     */
    public List<DocumentChunk> retrieveContext(String question, List<String> domainKeys) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        float[] embedding = azureOpenAiClient.embed(question).embedding();
        if (domainKeys == null || domainKeys.isEmpty()) {
            // No agent/domain scoping — search across all indexed documents in
            // the current tenant schema. Safe because TenantContext already
            // isolates the connection to the correct schema.
            return memoryRepository.retrieveAllChunks(embedding, retrievalTopK);
        }
        return memoryRepository.retrieveChunks(embedding, domainKeys, retrievalTopK);
    }

    // ---------------------------------------------------------------------------
    // Document management
    // ---------------------------------------------------------------------------

    public List<KnowledgeDocument> getDocuments(String domainKey) {
        if (domainKey == null || domainKey.isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, "domainKey is required");
        }
        return memoryRepository.findByDomain(domainKey);
    }

    public void archiveDocument(String documentKey) {
        memoryRepository.findByKey(documentKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Document not found: " + documentKey));
        memoryRepository.archiveDocument(documentKey);
    }

    public KnowledgeDocument updateDocumentMeta(String documentKey,
                                                 String domainKey,
                                                 String title,
                                                 String tags) {
        memoryRepository.findByKey(documentKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND, "Document not found: " + documentKey));
        memoryRepository.updateDocumentMeta(documentKey, domainKey, title, tags);
        return memoryRepository.findByKey(documentKey).orElseThrow();
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Extracts plain text from a document file.
     * Text-based files (txt, md, html) are read directly — Tika can silently
     * return blank text for these on some platforms. Binary formats (pdf, docx)
     * use Tika for structured extraction.
     */
    private String extractText(File file, String contentType) throws Exception {
        String name = file.getName().toLowerCase();
        boolean isPlainText = name.endsWith(".txt") || name.endsWith(".md")
                || name.endsWith(".html") || name.endsWith(".htm")
                || (contentType != null && (contentType.startsWith("text/")
                        || contentType.equals("application/xhtml+xml")));

        if (isPlainText) {
            return java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        }

        // PDF, DOCX, etc. — use Tika
        Tika tika = new Tika();
        org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
        String text = tika.parseToString(new FileInputStream(file), metadata, TIKA_MAX_CHARS);
        if (text == null || text.isBlank()) {
            log.warn("Tika returned blank text for {}; attempting raw read", file.getName());
            text = java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return text;
    }

    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot >= 0 && dot < fileName.length() - 1)
                ? fileName.substring(dot + 1) : "";
    }

    private String detectContentType(Path filePath) {
        try {
            String detected = Files.probeContentType(filePath);
            return detected != null ? detected : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    /**
     * Rough token estimate: 1 token ≈ 0.75 words (GPT tokeniser heuristic).
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) return 0;
        int wordCount = text.split("\\s+").length;
        return (int) Math.ceil(wordCount / 0.75);
    }
}
