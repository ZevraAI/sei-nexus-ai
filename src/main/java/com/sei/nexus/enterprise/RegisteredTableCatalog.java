package com.sei.nexus.enterprise;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The tables Zevra has registered for a connection (its data objects), qualified with their real
 * schema. AI helpers that need table names (the workflow generator, the agent router) use this
 * instead of listing a fixed schema of the source database: a company's data can live in any
 * schema, and the database's default schema may hold unrelated or sample tables.
 */
@Component
public class RegisteredTableCatalog {

    public record Entry(String qualifiedName, String businessName, List<String> columns) {}

    private final EnterpriseMapRepository repository;

    public RegisteredTableCatalog(EnterpriseMapRepository repository) {
        this.repository = repository;
    }

    /** Registered tables of one connection, with column names when {@code withColumns}. */
    public List<Entry> tables(String connectionKey, boolean withColumns) {
        return repository.findDataObjectsByConnection(connectionKey).stream()
                .map(o -> new Entry(qualified(o.schemaName(), o.tableName()), o.businessName(),
                        withColumns
                                ? repository.findColumnsByObject(o.objectKey()).stream()
                                        .map(DataColumn::columnName).toList()
                                : List.of()))
                .toList();
    }

    /** "schema.table (Business Name), ..." for a short prompt line; empty if none registered. */
    public String tableLine(String connectionKey) {
        return tables(connectionKey, false).stream()
                .map(e -> e.businessName() == null || e.businessName().isBlank()
                        ? e.qualifiedName()
                        : e.qualifiedName() + " (" + e.businessName() + ")")
                .collect(Collectors.joining(", "));
    }

    static String qualified(String schema, String table) {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }
}
