package com.sei.nexus.enterprise;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic content classification of sampled column values (PRO-27) —
 * stage 2 of the industry-standard two-stage defense for OBSERVED value-domain
 * discovery: column-name gates decide what gets <em>probed</em>; this classifier
 * decides what gets <em>persisted</em>.
 *
 * <p>Design constraints, per the PRO-26 research (P4) and the ownership
 * checkpoint: purely deterministic (a safety gate must be auditable and
 * model-independent — no AI call, no sampling of randomness), value-content
 * based (never only column names), and <b>fail-closed</b>: a false positive
 * costs the planner one advisory domain; a false negative costs privacy.
 * Uncertain shapes are rejected.
 *
 * <p>SAFE by default: short, token-like business values — statuses, types,
 * categories, codes, geographic names. UNSAFE detectors, in evaluation order:
 * email, SSN-like, long digit strings (phone/account/card), identifier
 * patterns (invoice/reference shapes), free text, person-name shapes
 * (dictionary-assisted, fraction-thresholded so state names like Georgia or
 * Virginia never trip it).
 */
final class SampleContentClassifier {

    private SampleContentClassifier() {}

    private static final Pattern EMAIL      = Pattern.compile(".+@.+\\..+");
    private static final Pattern SSN        = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    // letters-then-long-digit-run reference shapes: INV-2024-00187, PO_44321, DOC/20240001
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z]{0,5}[-_/#]?\\d{4,}[-_/]?\\d*$");
    // 2-3 capitalized alphabetic words — person-name SHAPE (shared with places/categories,
    // hence the dictionary + fraction requirements below)
    private static final Pattern TITLE_CASE_WORDS =
            Pattern.compile("^\\p{Lu}\\p{L}+(?: \\p{Lu}\\p{L}+){1,2}$");

    private static final int MAX_VALUE_LENGTH   = 80;   // any longer → free text
    private static final int MAX_WORDS_PER_VALUE = 6;   // any wordier → free text
    private static final int MAX_AVG_LENGTH      = 40;  // prose-shaped on average
    private static final double DIGIT_STRING_FRACTION = 0.5;
    private static final double IDENTIFIER_FRACTION   = 0.5;
    private static final double PERSON_NAME_FRACTION  = 0.4;

    /**
     * Common given names (lowercased) — the lightweight dictionary tier of the
     * industry classifier pattern. A value counts as person-name-shaped only if
     * it is multi-word title case AND its first token is in this set; single-word
     * values (Georgia, Virginia, Florence…) never count.
     */
    private static final Set<String> FIRST_NAMES = Set.of(
            "james", "john", "robert", "michael", "william", "david", "richard", "joseph",
            "thomas", "charles", "christopher", "daniel", "matthew", "anthony", "mark",
            "donald", "steven", "paul", "andrew", "joshua", "kenneth", "kevin", "brian",
            "george", "timothy", "ronald", "edward", "jason", "jeffrey", "ryan", "jacob",
            "gary", "nicholas", "eric", "jonathan", "stephen", "larry", "justin", "scott",
            "brandon", "benjamin", "samuel", "gregory", "alexander", "patrick", "frank",
            "raymond", "jack", "dennis", "jerry", "tyler", "aaron", "jose", "adam", "nathan",
            "henry", "peter", "carl", "arthur", "harold", "jordan", "mary", "patricia",
            "jennifer", "linda", "elizabeth", "barbara", "susan", "jessica", "sarah",
            "karen", "lisa", "nancy", "betty", "sandra", "margaret", "ashley", "kimberly",
            "emily", "donna", "michelle", "carol", "amanda", "melissa", "deborah",
            "stephanie", "rebecca", "sharon", "laura", "cynthia", "kathleen", "amy",
            "angela", "shirley", "anna", "brenda", "pamela", "emma", "nicole", "helen",
            "samantha", "katherine", "christine", "debra", "rachel", "carolyn", "janet",
            "catherine", "maria", "heather", "diane", "ruth", "julie", "olivia", "joyce",
            "grace", "victoria", "sophia", "isabella", "ahmed", "mohammed", "ali", "omar",
            "fatima", "aisha", "wei", "ming", "chen", "yuki", "hiroshi", "raj", "priya",
            "amit", "ananya", "carlos", "juan", "luis", "miguel", "sofia", "lucia", "pablo",
            "hans", "klaus", "ivan", "dmitri", "olga", "pierre", "marie", "claire");

    /**
     * @return {@code null} when the sample is safe to persist as an OBSERVED
     *         value domain; otherwise a short reason (safe to log — never
     *         contains sampled values).
     */
    static String rejectReason(List<String> values) {
        if (values == null || values.isEmpty()) return "empty sample";

        int digitStrings = 0, identifiers = 0, personNames = 0;
        long totalLength = 0;

        for (String raw : values) {
            String v = raw == null ? "" : raw.trim();
            totalLength += v.length();

            if (EMAIL.matcher(v).matches())  return "email-shaped values";
            if (SSN.matcher(v).find())       return "ssn-shaped values";
            if (v.length() > MAX_VALUE_LENGTH) return "free-text values (length)";
            if (countWords(v) > MAX_WORDS_PER_VALUE) return "free-text values (word count)";

            String digitsOnly = v.replaceAll("[\\s\\-().+/]", "");
            if (digitsOnly.length() >= 7 && digitsOnly.length() <= 19
                    && digitsOnly.chars().allMatch(Character::isDigit)) {
                digitStrings++;
            }
            if (IDENTIFIER.matcher(v).matches()) identifiers++;
            if (TITLE_CASE_WORDS.matcher(v).matches()
                    && FIRST_NAMES.contains(firstToken(v))) {
                personNames++;
            }
        }

        int n = values.size();
        if ((double) digitStrings / n >= DIGIT_STRING_FRACTION) {
            return "phone/account-shaped digit strings";
        }
        if ((double) identifiers / n >= IDENTIFIER_FRACTION) {
            return "identifier-shaped values (invoice/reference pattern)";
        }
        if (personNames >= 2 && (double) personNames / n >= PERSON_NAME_FRACTION) {
            return "person-name-shaped values";
        }
        if ((double) totalLength / n > MAX_AVG_LENGTH) {
            return "free-text values (average length)";
        }
        return null;
    }

    private static int countWords(String v) {
        if (v.isBlank()) return 0;
        return v.split("\\s+").length;
    }

    private static String firstToken(String v) {
        int space = v.indexOf(' ');
        return (space > 0 ? v.substring(0, space) : v).toLowerCase(Locale.ROOT);
    }
}
