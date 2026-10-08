package io.dataease.enterprise.foundation;

import io.dataease.dao.auto.entity.DeStandaloneVersion;
import io.dataease.initSql.SqlBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/** Group 4 is append-only and contiguous. Validation is read-only and precedes every group's execution. */
public final class EnterpriseMigrationHistory {
    private EnterpriseMigrationHistory() { }

    public static boolean ownsVersion(String version) {
        return "4".equals(version) || version != null && version.startsWith("4.");
    }

    private static int minor(String version) {
        if (version == null || !version.matches("4\\.[1-9][0-9]{0,8}")) invalid();
        return Integer.parseInt(version.substring(2));
    }

    public static void validate(List<SqlBlock> blocks, List<DeStandaloneVersion> records) {
        if (blocks == null || blocks.isEmpty() || records == null) invalid();
        var steps = new TreeSet<Integer>();
        for (var block : blocks) {
            if (block == null || !"4".equals(block.getVersionGroup()) || block.getVersion() == null) invalid();
            if (!steps.add(minor(block.getVersion().getVersion()))) invalid();
        }
        if (steps.first() != 1 || steps.last() != steps.size()) invalid();
        var history = new ArrayList<DeStandaloneVersion>();
        var ranks = new HashSet<Integer>();
        for (var record : records) {
            if (record == null) invalid();
            if (!ownsVersion(record.getVersion())) continue;
            if (record.getId() == null || record.getId() <= 0 || !ranks.add(record.getId())
                    || record.getSuccess() == null || !steps.contains(minor(record.getVersion()))) invalid();
            history.add(record);
        }
        history.sort(Comparator.comparing(DeStandaloneVersion::getId));
        int expected = 1;
        for (var record : history) {
            if (minor(record.getVersion()) != expected) invalid();
            if (record.getSuccess()) expected++;
        }
        long pending = steps.last() - expected + 1L;
        int maximumRank = records.stream().map(DeStandaloneVersion::getId).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(0);
        if (pending > 0 && maximumRank + pending > Integer.MAX_VALUE) invalid();
    }

    private static void invalid() {
        throw new IllegalStateException("Enterprise migration plan or history is unsupported or inconsistent; no migrations executed");
    }
}
