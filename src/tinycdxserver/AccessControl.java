package tinycdxserver;

import com.google.gson.Gson;
import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory;
import com.googlecode.concurrenttrees.radixinverted.ConcurrentInvertedRadixTree;
import com.googlecode.concurrenttrees.radixinverted.InvertedRadixTree;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Manages a set of access control rules and policies. Rules are persisted
 * in RocksDB but are also kept in-memory in a radix tree for fast filtering
 * of results.
 */
class AccessControl {
    private final static Gson gson = new Gson();

    private final Map<Long,AccessPolicy> policies;
    private final Map<Long,AccessRule> rules;
    private final RulesBySurt rulesBySurt;
    private final RocksDB db;
    private final ColumnFamilyHandle ruleCf, policyCf;
    private final AtomicLong nextRuleId, nextPolicyId;

    public AccessControl(RocksDB db, ColumnFamilyHandle ruleCf, ColumnFamilyHandle policyCf) {
        this.db = db;
        this.ruleCf = ruleCf;
        this.policyCf = policyCf;

        rules = loadRules(db, ruleCf);
        policies = loadPolicies(db, policyCf);

        rulesBySurt = new RulesBySurt(rules.values());

        nextRuleId = new AtomicLong(calculateNextId(db, ruleCf));
        nextPolicyId = new AtomicLong(calculateNextId(db, policyCf));
    }

    private long calculateNextId(RocksDB db, ColumnFamilyHandle cf) {
        try (RocksIterator it = db.newIterator(cf)) {
            it.seekToLast();
            return it.isValid() ? decodeKey(it.key()) + 1 : 0;
        }
    }

    private static Map<Long,AccessPolicy> loadPolicies(RocksDB db, ColumnFamilyHandle policyCf) {
        Map<Long,AccessPolicy> map = new TreeMap<>();
        try (RocksIterator it = db.newIterator(policyCf)) {
            it.seekToFirst();
            while (it.isValid()) {
                AccessPolicy policy = gson.fromJson(new String(it.value(), UTF_8), AccessPolicy.class);
                map.put(policy.id, policy);
                it.next();
            }
        }
        return map;
    }

    private static Map<Long, AccessRule> loadRules(RocksDB db, ColumnFamilyHandle ruleCf) {
        Map<Long,AccessRule> map = new TreeMap<>();
        try (RocksIterator it = db.newIterator(ruleCf)) {
            it.seekToFirst();
            while (it.isValid()) {
                AccessRule rule = gson.fromJson(new String(it.value(), UTF_8), AccessRule.class);
                map.put(rule.id, rule);
                it.next();
            }
        }
        return map;
    }

    /**
     * List all access control rules in the database.
     */
    public Collection<AccessRule> list() {
        return rules.values();
    }

    /**
     * Save an access control rule to the database.
     */
    public long put(AccessRule rule) throws RocksDBException {
        if (rule.id == null) {
            rule.id = nextRuleId.getAndIncrement();
        }
        byte[] value = gson.toJson(rule).getBytes(UTF_8);
        db.put(ruleCf, encodeKey(rule.id), value);

        AccessRule previous = rules.put(rule.id, rule);
        if (previous != null) {
            rulesBySurt.remove(previous);
        }
        // XXX: race
        rulesBySurt.put(rule);

        return rule.id;
    }

    /**
     * Save an access control policy to the database.
     */
    public long put(AccessPolicy policy) throws RocksDBException {
        if (policy.id == null) {
            policy.id = nextPolicyId.getAndIncrement();
        }
        byte[] value = gson.toJson(policy).getBytes(UTF_8);
        db.put(ruleCf, encodeKey(policy.id), value);
        return policy.id;
    }

    /**
     * Find the most specific rule which matches the given capture and access
     * time.
     */
    private AccessRule ruleForCapture(Capture capture, Date accessTime) {
        AccessRule matching = null;
        for (AccessRule rule : rulesForSurt(capture.urlkey)) {
            if (rule.matchesDates(capture.date(), accessTime)) {
                matching = rule;
            }
        }
        return matching;
    }

    /**
     * Find all rules that may apply to the given SURT.
     */
    public List<AccessRule> rulesForSurt(String surt) {
        return rulesBySurt.prefixing(surt);
    }

    /**
     * Returns a predicate which can be used to filter a list of captures.
     */
    public Predicate<Capture> filter(String accessPoint, Date accessTime) {
        return capture -> {
            AccessRule rule = ruleForCapture(capture, accessTime);
            if (rule != null) {
                if (!rule.policy.permittedAccessPoints.contains(accessPoint)) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * Lookup an access rule by id.
     */
    public AccessRule rule(long ruleId) throws RocksDBException {
        return rules.get(ruleId);
    }

    static long decodeKey(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(BIG_ENDIAN).getLong(0);
    }

    static byte[] encodeKey(long ruleId) {
        return ByteBuffer.allocate(8).order(BIG_ENDIAN).putLong(ruleId).array();
    }

    /**
     * A secondary index for looking up access control URLs which prefix a
     * given SURT.
     */
    static class RulesBySurt {
        private final InvertedRadixTree<List<AccessRule>> tree;

        RulesBySurt(Collection<AccessRule> rules) {
            tree = new ConcurrentInvertedRadixTree<>(new DefaultCharArrayNodeFactory());
            for (AccessRule rule: rules) {
                put(rule);
            }
        }

        /**
         * Add an AccessRule to the radix tree. The rule will be added multiple times,
         * once for each SURT prefix.
         */
        void put(AccessRule rule) {
            for (String surt: rule.surts) {
                List<AccessRule> list = tree.getValueForExactKey(surt);
                if (list == null) {
                    list = Collections.synchronizedList(new ArrayList<>());
                    tree.put(surt, list);
                }
                list.add(rule);
            }
        }

        void remove(AccessRule rule) {
            for (String surt : rule.surts) {
                List<AccessRule> list = tree.getValueForExactKey(surt);
                list.remove(rule);
            }
        }

        List<AccessRule> prefixing(String surt) {
            return flatten(tree.getValuesForKeysPrefixing(surt));
        }

        static List<AccessRule> flatten(Iterable<List<AccessRule>> listsOfRules) {
            // XXX make lazy?
            ArrayList<AccessRule> result = new ArrayList<>();
            listsOfRules.forEach(result::addAll);
            return result;
        }
    }
}