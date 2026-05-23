package com.sei.nexus.governance;

import java.util.List;

/**
 * Result produced by {@link DataContractService#evaluate}.
 */
public record ContractResult(
        ContractStatus status,
        List<String>   contractsChecked,
        List<String>   contractsViolated,
        List<String>   violationMessages,  // human-readable explanations shown to the user
        String         remediatedSql       // non-null only when status = REMEDIATED
) {

    public enum ContractStatus {
        /** All contracts passed — proceed with original SQL. */
        PASSED,
        /** A BLOCK-enforcement contract was violated — do not execute. */
        BLOCKED,
        /** A WARN-enforcement contract was violated — execute but log. */
        WARNED,
        /** SQL was automatically rewritten to satisfy the contract. */
        REMEDIATED
    }

    public static ContractResult passed(List<String> checked) {
        return new ContractResult(ContractStatus.PASSED, checked, List.of(), List.of(), null);
    }

    public boolean isBlocked() {
        return status == ContractStatus.BLOCKED;
    }

    public String effectiveSql(String originalSql) {
        return remediatedSql != null ? remediatedSql : originalSql;
    }
}
