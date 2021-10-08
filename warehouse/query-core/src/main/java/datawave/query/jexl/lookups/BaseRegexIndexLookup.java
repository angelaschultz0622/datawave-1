package datawave.query.jexl.lookups;

import com.google.common.collect.Sets;
import datawave.query.config.ShardQueryConfiguration;
import datawave.query.tables.ScannerFactory;

import java.util.Set;

public abstract class BaseRegexIndexLookup extends ThreadedIndexLookup {
    public static final Set<String> DISALLOWED_PATTERNS = Sets.newHashSet(".*", ".*?");
    
    public BaseRegexIndexLookup(ShardQueryConfiguration config, ScannerFactory scannerFactory, boolean supportReference) {
        super(config, scannerFactory, supportReference);
    }
    
    public static boolean isAcceptedPattern(String pattern) {
        return !DISALLOWED_PATTERNS.contains(pattern);
    }
}