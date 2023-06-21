package datawave.query.jexl.functions;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import datawave.query.attributes.AttributeFactory;
import datawave.query.config.ShardQueryConfiguration;
import datawave.query.exceptions.DatawaveFatalQueryException;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.functions.arguments.JexlArgumentDescriptor;
import datawave.query.jexl.visitors.EventDataQueryExpressionVisitor;
import datawave.query.util.DateIndexHelper;
import datawave.query.util.MetadataHelper;
import datawave.webservice.query.exception.DatawaveErrorCode;
import datawave.webservice.query.exception.QueryException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.commons.jexl2.parser.ASTFunctionNode;
import org.apache.commons.jexl2.parser.JexlNode;
import org.apache.log4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GroupingRequiredFilterFunctionsDescriptor implements JexlFunctionArgumentDescriptorFactory {

    private static final Logger log = Logger.getLogger(GroupingRequiredFilterFunctionsDescriptor.class);

    /**
     * This is the argument descriptor which can be used to normalize and optimize function node queries
     */
    public static class GroupingRequiredFilterJexlArgumentDescriptor implements JexlArgumentDescriptor {
        private static final ImmutableSet<String> groupingRequiredFunctions = ImmutableSet.of("atomValuesMatch", "matchesInGroup", "matchesInGroupLeft",
                        "getGroupsForMatchesInGroup");

        private final ASTFunctionNode node;

        public GroupingRequiredFilterJexlArgumentDescriptor(ASTFunctionNode node) {
            this.node = node;
        }

        /**
         * Returns 'true' because none of these functions should influence the index query.
         */
        @Override
        public JexlNode getIndexQuery(ShardQueryConfiguration config, MetadataHelper helper, DateIndexHelper dateIndexHelper, Set<String> datatypeFilter) {
            FunctionJexlNodeVisitor functionMetadata = new FunctionJexlNodeVisitor();
            node.jjtAccept(functionMetadata, null);

            // 'true' is returned to imply that there is no range lookup possible for this function
            return TRUE_NODE;
        }

        @Override
        public void addFilters(AttributeFactory attributeFactory, Map<String,EventDataQueryExpressionVisitor.ExpressionFilter> filterMap) {
            FunctionJexlNodeVisitor functionMetadata = new FunctionJexlNodeVisitor();
            node.jjtAccept(functionMetadata, null);

            if (functionMetadata.name().equals("atomValuesMatch")) {
                // special case
                Set<String> fields = new HashSet<>();

                for (JexlNode node : functionMetadata.args()) {
                    fields.addAll(JexlASTHelper.getIdentifierNames(node));
                }
                for (String fieldName : fields) {
                    EventDataQueryExpressionVisitor.ExpressionFilter f = filterMap.get(fieldName);
                    if (f == null) {
                        filterMap.put(fieldName, f = new EventDataQueryExpressionVisitor.ExpressionFilter(attributeFactory, fieldName));
                    }
                    f.acceptAllValues();
                }
            } else {
                // don't include the last argument if the size is odd as that is a position arg
                for (int i = 0; i < functionMetadata.args().size() - 1; i += 2) {
                    Set<String> fields = JexlASTHelper.getIdentifierNames(functionMetadata.args().get(i));
                    JexlNode valueNode = functionMetadata.args().get(i + 1);
                    for (String fieldName : fields) {
                        EventDataQueryExpressionVisitor.ExpressionFilter f = filterMap.get(fieldName);
                        if (f == null) {
                            filterMap.put(fieldName, f = new EventDataQueryExpressionVisitor.ExpressionFilter(attributeFactory, fieldName));
                        }
                        f.addFieldPattern(valueNode.image);
                    }
                }
            }
        }

        @Override
        public Set<String> fieldsForNormalization(MetadataHelper helper, Set<String> datatypeFilter, int arg) {
            try {
                Set<String> allFields = helper.getAllFields(datatypeFilter);
                Set<String> filteredFields = Sets.newHashSet();

                FunctionJexlNodeVisitor functionMetadata = new FunctionJexlNodeVisitor();
                node.jjtAccept(functionMetadata, null);
                if ((!functionMetadata.name().equals("atomValuesMatch")) && ((arg % 2) == 1)) {
                    Set<String> fields = JexlASTHelper.getIdentifierNames(functionMetadata.args().get(arg - 1));
                    for (String field : fields) {
                        filterField(allFields, field, filteredFields);
                    }
                    return filteredFields;
                }
                return Collections.emptySet();
            } catch (TableNotFoundException e) {
                QueryException qe = new QueryException(DatawaveErrorCode.METADATA_TABLE_FETCH_ERROR, e);
                log.error(qe);
                throw new DatawaveFatalQueryException(qe);
            }
        }

        @Override
        public Set<String> fields(MetadataHelper helper, Set<String> datatypeFilter) {
            try {
                Set<String> allFields = helper.getAllFields(datatypeFilter);
                Set<String> fields = Sets.newHashSet();

                FunctionJexlNodeVisitor functionMetadata = new FunctionJexlNodeVisitor();
                node.jjtAccept(functionMetadata, null);
                if (functionMetadata.name().equals("atomValuesMatch")) {
                    for (JexlNode node : functionMetadata.args()) {
                        fields.addAll(JexlASTHelper.getIdentifierNames(node));
                    }
                } else {
                    // don't include the last argument if the size is odd as that is a position arg
                    for (int i = 0; i < functionMetadata.args().size() - 1; i += 2) {
                        fields.addAll(JexlASTHelper.getIdentifierNames(functionMetadata.args().get(i)));
                    }
                }

                return filterField(allFields, fields);
            } catch (TableNotFoundException e) {
                QueryException qe = new QueryException(DatawaveErrorCode.METADATA_TABLE_FETCH_ERROR, e);
                log.error(qe);
                throw new DatawaveFatalQueryException(qe);
            }
        }

        @Override
        public Set<Set<String>> fieldSets(MetadataHelper helper, Set<String> datatypeFilter) {
            try {
                FunctionJexlNodeVisitor functionMetadata = new FunctionJexlNodeVisitor();
                node.jjtAccept(functionMetadata, null);
                Set<Set<String>> fieldSets = JexlArgumentDescriptor.Fields.product(functionMetadata.args().get(0));
                Set<Set<String>> filteredSets = Sets.newHashSet(Sets.newHashSet());
                Set<String> allFields = helper.getAllFields(datatypeFilter);

                if (functionMetadata.name().equals("atomValuesMatch")) {
                    // don't include the last argument if the size is odd as that is a position arg
                    for (int i = 1; i < functionMetadata.args().size(); i++) {
                        fieldSets = JexlArgumentDescriptor.Fields.product(fieldSets, functionMetadata.args().get(i));
                    }
                } else {
                    // don't include the last argument if the size is odd as that is a position arg
                    for (int i = 2; i < functionMetadata.args().size() - 1; i += 2) {
                        fieldSets = JexlArgumentDescriptor.Fields.product(fieldSets, functionMetadata.args().get(i));
                    }
                }

                for (Set<String> aFieldSet : fieldSets) {
                    filteredSets.add(filterField(allFields, aFieldSet));
                }

                return filteredSets;
            } catch (TableNotFoundException e) {
                QueryException qe = new QueryException(DatawaveErrorCode.METADATA_TABLE_FETCH_ERROR, e);
                log.error(qe);
                throw new DatawaveFatalQueryException(qe);
            }
        }

        /**
         * Given a list of all possible fields, filters out fields based on the given datatype(s)
         *
         * @param allFields
         * @param fieldToAdd
         * @param returnedFields
         */
        private void filterField(Set<String> allFields, String fieldToAdd, Set<String> returnedFields) {
            if (allFields.contains(fieldToAdd)) {
                returnedFields.add(fieldToAdd);
            }
        }

        /**
         * Given a list of all possible fields, filters out fields based on the given datatype(s)
         *
         * @param allFields
         * @param fields
         */
        private Set<String> filterField(Set<String> allFields, Set<String> fields) {
            Set<String> returnedFields = Sets.newHashSet();
            returnedFields.addAll(allFields);
            returnedFields.retainAll(fields);
            return returnedFields;
        }

        @Override
        public boolean useOrForExpansion() {
            FunctionJexlNodeVisitor functionMetadata = new FunctionJexlNodeVisitor();
            node.jjtAccept(functionMetadata, null);
            return true;
        }

        @Override
        public boolean regexArguments() {
            FunctionJexlNodeVisitor functionMetadata = new FunctionJexlNodeVisitor();
            node.jjtAccept(functionMetadata, null);
            return false;
        }

        @Override
        public boolean allowIvaratorFiltering() {
            return true;
        }
    }

    @Override
    public JexlArgumentDescriptor getArgumentDescriptor(ASTFunctionNode node) {
        try {
            Class<?> clazz = GetFunctionClass.get(node);
            if (!GroupingRequiredFilterFunctions.class.equals(clazz)) {
                throw new IllegalArgumentException(
                                "Calling " + this.getClass().getSimpleName() + ".getArgumentDescriptor with node for a function in " + clazz);
            }
            return new GroupingRequiredFilterJexlArgumentDescriptor(node);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
