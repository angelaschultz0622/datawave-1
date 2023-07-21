package datawave.ingest.mapreduce.handler.edge;

import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.commons.jexl2.Script;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.util.Assert;

import datawave.data.normalizer.DateNormalizer;
import datawave.ingest.data.Type;
import datawave.ingest.data.TypeRegistry;
import datawave.ingest.data.config.ConfigurationHelper;
import datawave.ingest.mapreduce.handler.edge.define.EdgeDefinition;
import datawave.ingest.mapreduce.handler.edge.define.EdgeDefinitionConfigurationHelper;
import datawave.ingest.mapreduce.handler.edge.evaluation.EdgePreconditionArithmetic;
import datawave.ingest.mapreduce.handler.edge.evaluation.EdgePreconditionCacheHelper;
import datawave.ingest.mapreduce.handler.edge.evaluation.EdgePreconditionJexlContext;
import datawave.ingest.mapreduce.handler.edge.evaluation.EdgePreconditionJexlEvaluation;

public class EdgeIngestConfiguration {

    public static final String EDGE_TABLE_DENYLIST_VALUES = ".protobufedge.table.denylist.values";
    public static final String EDGE_TABLE_DENYLIST_FIELDS = ".protobufedge.table.denylist.fields";
    public static final String EDGE_TABLE_METADATA_ENABLE = "protobufedge.table.metadata.enable";
    public static final String EDGE_TABLE_DENYLIST_ENABLE = "protobufedge.table.denylist.enable";
    public static final String EDGE_SPRING_CONFIG = "protobufedge.spring.config";
    public static final String EDGE_SPRING_RELATIONSHIPS = "protobufedge.table.relationships";
    public static final String EDGE_SPRING_COLLECTIONS = "protobufedge.table.collections";
    public static final String ACTIVITY_DATE_FUTURE_DELTA = "protobufedge.valid.activitytime.future.delta";
    public static final String ACTIVITY_DATE_PAST_DELTA = "protobufedge.valid.activitytime.past.delta";
    public static final String EVALUATE_PRECONDITIONS = "protobufedge.evaluate.preconditions";
    public static final String INCLUDE_ALL_EDGES = "protobufedge.include.all.edges";
    public static final String EDGE_DEFAULT_DATA_TYPE = "default";
    public static final String TRIM_FIELD_GROUP = ".trim.field.group";

    private boolean enableDenylist = false;
    private boolean enableMetadata;
    private boolean evaluatePreconditions = false;
    private boolean includeAllEdges;

    private String springConfigFile;

    private Long futureDelta, pastDelta, newFormatStartDate;

    private Map<String,Map<String,String>> edgeEnrichmentTypeLookup = new HashMap<>();
    private Map<String,Set<String>> denylistFieldLookup = new HashMap<>();
    private Map<String,Set<String>> denylistValueLookup = new HashMap<>();
    private Map<String,Script> scriptCache;

    private EdgePreconditionJexlContext edgePreconditionContext;
    private EdgePreconditionJexlEvaluation edgePreconditionEvaluation;
    private EdgePreconditionCacheHelper edgePreconditionCacheHelper;
    private EdgePreconditionArithmetic arithmetic = new EdgePreconditionArithmetic();

    protected HashSet<String> edgeRelationships = new HashSet<>();
    protected HashSet<String> collectionType = new HashSet<>();

    protected EdgeKeyVersioningCache versioningCache = null;

    protected Map<String,EdgeDefinitionConfigurationHelper> edges = null;

    private static final Logger log = LoggerFactory.getLogger(EdgeIngestConfiguration.class);

    public EdgeIngestConfiguration(Configuration conf) {

        this.enableDenylist = ConfigurationHelper.isNull(conf, EDGE_TABLE_DENYLIST_ENABLE, Boolean.class);
        this.enableMetadata = ConfigurationHelper.isNull(conf, EDGE_TABLE_METADATA_ENABLE, Boolean.class);

        springConfigFile = ConfigurationHelper.isNull(conf, EDGE_SPRING_CONFIG, String.class);
        futureDelta = ConfigurationHelper.isNull(conf, ACTIVITY_DATE_FUTURE_DELTA, Long.class);
        pastDelta = ConfigurationHelper.isNull(conf, ACTIVITY_DATE_PAST_DELTA, Long.class);

        evaluatePreconditions = Boolean.parseBoolean(conf.get(EVALUATE_PRECONDITIONS));
        includeAllEdges = Boolean.parseBoolean(conf.get(INCLUDE_ALL_EDGES));

        setupVersionCache(conf);
        // This will fail if the TypeRegistry has not been initialized in the VM.
        TypeRegistry registry = TypeRegistry.getInstance(conf);
        readEdgeConfigFile(registry, springConfigFile);
        pruneEdges();

    }

    /**
     * Parse and Store the Edge defs by data type
     */
    public void readEdgeConfigFile(TypeRegistry registry, String springConfigFile) {

        ClassPathXmlApplicationContext ctx = null;

        edges = new HashMap<>();

        try {
            ctx = new ClassPathXmlApplicationContext(ProtobufEdgeDataTypeHandler.class.getClassLoader().getResource(springConfigFile).toString());

            log.info("Got config on first try!");
        } catch (Exception e) {
            log.error("Problem getting config for ProtobufEdgeDataTypeHandler: {}", e);
            throw e;
        }

        Assert.notNull(ctx);

        registry.put(EDGE_DEFAULT_DATA_TYPE, null);

        if (ctx.containsBean(EDGE_SPRING_RELATIONSHIPS) && ctx.containsBean(EDGE_SPRING_COLLECTIONS)) {
            edgeRelationships.addAll((HashSet<String>) ctx.getBean(EDGE_SPRING_RELATIONSHIPS));
            collectionType.addAll((HashSet<String>) ctx.getBean(EDGE_SPRING_COLLECTIONS));
        } else {
            log.error("Edge relationships and or collection types are not configured correctly. Cannot build edge definitions");
            throw new RuntimeException("Missing some spring configurations");
        }

        for (Map.Entry<String,Type> entry : registry.entrySet()) {
            if (ctx.containsBean(entry.getKey())) {
                EdgeDefinitionConfigurationHelper edgeConfHelper = (EdgeDefinitionConfigurationHelper) ctx.getBean(entry.getKey());

                // Always call init first before getting getting edge defs. This performs validation on the config file
                // and builds the edge pairs/groups
                edgeConfHelper.init(edgeRelationships, collectionType);

                edges.put(entry.getKey(), edgeConfHelper);
                if (edgeConfHelper.getEnrichmentTypeMappings() != null) {
                    edgeEnrichmentTypeLookup.put(entry.getKey(), edgeConfHelper.getEnrichmentTypeMappings());
                }
            }

            if (ctx.containsBean(entry.getKey() + EDGE_TABLE_DENYLIST_VALUES)) {
                Set<String> values = (HashSet<String>) ctx.getBean(entry.getKey() + EDGE_TABLE_DENYLIST_VALUES);
                denylistValueLookup.put(entry.getKey(), new HashSet<>(values));
            }

            if (ctx.containsBean(entry.getKey() + EDGE_TABLE_DENYLIST_FIELDS)) {
                Set<String> fields = (HashSet<String>) ctx.getBean(entry.getKey() + EDGE_TABLE_DENYLIST_FIELDS);
                denylistFieldLookup.put(entry.getKey(), new HashSet<>(fields));
            }

        }
        ctx.close();

        registry.remove(EDGE_DEFAULT_DATA_TYPE);
    }

    public void pruneEdges() {

        setUpPreconditions();

        removeDenyListedEdges();

        log.info("Found edge definitions for " + edges.keySet().size() + " data types.");

        StringBuffer sb = new StringBuffer();
        sb.append("Data Types With Defined Edges: ");
        for (String t : edges.keySet()) {
            sb.append(t).append(" ");
        }
        log.info(sb.toString());

    }

    public void setupVersionCache(Configuration conf) {
        if (this.versioningCache == null) {
            this.versioningCache = new EdgeKeyVersioningCache(conf);
        }
    }

    public long getStartDateIfValid() {
        long newFormatStartDate;
        try {
            // Only one known edge key version so we simply grab the first one here

            // expected to be in the "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" format
            String startDate = versioningCache.getEdgeKeyVersionDateChange().entrySet().iterator().next().getValue();
            newFormatStartDate = DateNormalizer.parseDate(startDate, DateNormalizer.FORMAT_STRINGS).getTime();
            log.info("Edge key version change date set to: " + startDate);
        } catch (IOException e) {
            log.error("IO Exception could not get edge key version cache, will not generate edges!");
            throw new RuntimeException("IO Exception could not get edge key version cache " + e.getMessage());
        } catch (ParseException e) {
            log.error("Unable to parse the switchover date will not generate edges!");
            throw new RuntimeException("Protobufedge handler config not set correctly " + e.getMessage());

        } catch (NoSuchElementException e) {
            log.error("edge key version cache existed but was empty, will not generate edges");
            throw new RuntimeException("Edge key versioning cache is empty " + e.getMessage());
        }
        return newFormatStartDate;
    }

    private void removeDenyListedEdges() {
        // loop through edge definitions and collect any ones that have denylisted fields
        if (this.enableDenylist) {
            Map<String,Set<EdgeDefinition>> denylistedEdges = new HashMap<>();
            for (String dType : edges.keySet()) {
                if (!denylistedEdges.containsKey(dType)) {
                    denylistedEdges.put(dType, new HashSet<>());
                }
                for (EdgeDefinition edgeDef : edges.get(dType).getEdges()) {
                    if (isDenylistField(dType, edgeDef.getSourceFieldName()) || isDenylistField(dType, edgeDef.getSinkFieldName())) {
                        denylistedEdges.get(dType).add(edgeDef);
                        log.warn("Removing Edge Definition due to denylisted Field: DataType: " + dType + " Definition: " + edgeDef.getSourceFieldName() + "-"
                                        + edgeDef.getSinkFieldName());
                    } else if (edgeDef.isEnrichmentEdge()) {
                        if (isDenylistField(dType, edgeDef.getEnrichmentField())) {
                            denylistedEdges.get(dType).add(edgeDef);
                        }
                    }
                }
            }
            // remove the denylistedEdges
            int denylistedFieldCount = 0;
            for (String dType : denylistedEdges.keySet()) {
                for (EdgeDefinition edgeDef : denylistedEdges.get(dType)) {
                    edges.get(dType).getEdges().remove(edgeDef);
                    denylistedFieldCount++;
                }
            }
            if (denylistedFieldCount > 0) {
                log.info("Removed " + denylistedFieldCount + " edge definitions because they contain denylisted fields.");
            }
        } else {
            log.info("Denylisting of edges is disabled.");
        }
    }

    /*
     * The evaluate preconditions boolean determines whether or not we want to set up the Jexl Contexts to run preconditions the includeAllEdges boolean
     * determines whether we want the extra edge definitions from the preconditions included There are three scenarios: the first is you want to run and
     * evaluate preconditions. The evaluatePreconditions boolean will be set to true, the edges will be added, the jext contexts will be set up, and the
     * conditional edges will be evaluated.
     *
     * Second, you don't want to evaluate the conditional edges, but you want them included. The evaluatePreconditions boolean will be set to false so the jexl
     * contexts are not set up, but the includeAllEdges boolean will be set to true.
     *
     * Third, you want neither of these done. Both booleans are set to false. The jexl context isn't set up and the conditional edges will be removed as to not
     * waste time evaluating edges where the conditions won't be met
     */
    public void setUpPreconditions() {
        // Set up the EdgePreconditionJexlContext, if enabled
        if (evaluatePreconditions) {
            edgePreconditionContext = new EdgePreconditionJexlContext(edges);
            edgePreconditionEvaluation = new EdgePreconditionJexlEvaluation();
            edgePreconditionCacheHelper = new EdgePreconditionCacheHelper(arithmetic);
            scriptCache = edgePreconditionCacheHelper.createScriptCacheFromEdges(edges);
        } else if (!includeAllEdges) {
            // Else remove edges with a precondition. No conditional edge defs will be evaluated possibly resulting in fewer edges
            log.info("Removing conditional edge definitions, possibly resulting in fewer edges being created");
            removeEdgesWithPreconditions();
        }
    }

    /**
     * When EVALUATE_PRECONDITIONS is false, remove edges with preconditions from consideration.
     */
    private void removeEdgesWithPreconditions() {
        Map<String,Set<EdgeDefinition>> preconditionEdges = new HashMap<>();
        for (String dType : edges.keySet()) {
            if (!preconditionEdges.containsKey(dType)) {
                preconditionEdges.put(dType, new HashSet<>());
            }
            for (EdgeDefinition edgeDef : edges.get(dType).getEdges()) {
                if (edgeDef.hasJexlPrecondition()) {
                    preconditionEdges.get(dType).add(edgeDef);
                }
            }
        }

        // remove the edges with preconditions
        int removedCount = 0;
        for (String dType : preconditionEdges.keySet()) {
            for (EdgeDefinition edgeDef : preconditionEdges.get(dType)) {
                edges.get(dType).getEdges().remove(edgeDef);
                removedCount++;
            }
        }
        if (log.isTraceEnabled()) {
            log.trace("Removed " + removedCount + " edges with preconditions prior to event processing.");
        }

    }

    public Map<String,Set<String>> getDenylistFieldLookup() {
        return denylistFieldLookup;
    }

    public Map<String,Set<String>> getDenylistValueLookup() {
        return denylistValueLookup;
    }

    private boolean isDenylistField(String dataType, String fieldName) {
        if (denylistFieldLookup.containsKey(dataType)) {
            return this.denylistFieldLookup.get(dataType).contains(fieldName);
        } else if (denylistFieldLookup.containsKey(EDGE_DEFAULT_DATA_TYPE)) {
            // perhaps there is no denylist, which is fine
            return this.denylistFieldLookup.get(EDGE_DEFAULT_DATA_TYPE).contains(fieldName);
        }
        return false;
    }

    public boolean isDenylistValue(String dataType, String fieldValue) {
        if (denylistValueLookup.containsKey(dataType)) {
            return this.denylistValueLookup.get(dataType).contains(fieldValue);
        } else if (denylistValueLookup.containsKey(EDGE_DEFAULT_DATA_TYPE)) {
            // perhaps there is no denylist, which is fine
            return this.denylistValueLookup.get(EDGE_DEFAULT_DATA_TYPE).contains(fieldValue);
        }
        return false;
    }

    public Map<String,EdgeDefinitionConfigurationHelper> getEdges() {
        return edges;
    }

    public void setEdges(Map<String,EdgeDefinitionConfigurationHelper> edges) {
        this.edges = edges;
    }

    public void setVersioningCache(EdgeKeyVersioningCache versioningCache) {
        this.versioningCache = versioningCache;
    }

    public boolean evaluatePreconditions() {
        return evaluatePreconditions;
    }

    public EdgePreconditionJexlContext getPreconditionContext() {
        return edgePreconditionContext;
    }

    public EdgePreconditionJexlEvaluation getEdgePreconditionEvaluation() {
        return edgePreconditionEvaluation;
    }

    public Map<String,Script> getScriptCache() {
        return scriptCache;
    }

    public boolean enableDenylist() {
        return enableDenylist;
    }

    public Map<String,Map<String,String>> getEdgeEnrichmentTypeLookup() {
        return edgeEnrichmentTypeLookup;
    }

    public void clearArithmeticMatchingGroups() {
        arithmetic.clearMatchingGroups();
    }

    public Map<String,Set<String>> getArithmeticMatchingGroups() {
        return arithmetic.getMatchingGroups();
    }

    public long getPastDelta() {
        return pastDelta;
    }

    public long getFutureDelta() {
        return futureDelta;
    }
}
