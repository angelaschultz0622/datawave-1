package datawave.query.jexl.visitors;

import datawave.marking.MarkingFunctions;
import datawave.query.exceptions.DatawaveFatalQueryException;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.functions.JexlFunctionArgumentDescriptorFactory;
import datawave.query.jexl.functions.arguments.JexlArgumentDescriptor;
import datawave.query.util.MetadataHelper;
import datawave.webservice.query.exception.DatawaveErrorCode;
import datawave.webservice.query.exception.QueryException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.commons.jexl2.parser.ASTAndNode;
import org.apache.commons.jexl2.parser.ASTEQNode;
import org.apache.commons.jexl2.parser.ASTERNode;
import org.apache.commons.jexl2.parser.ASTFunctionNode;
import org.apache.commons.jexl2.parser.ASTGENode;
import org.apache.commons.jexl2.parser.ASTGTNode;
import org.apache.commons.jexl2.parser.ASTIdentifier;
import org.apache.commons.jexl2.parser.ASTJexlScript;
import org.apache.commons.jexl2.parser.ASTLENode;
import org.apache.commons.jexl2.parser.ASTLTNode;
import org.apache.commons.jexl2.parser.ASTNENode;
import org.apache.commons.jexl2.parser.ASTNRNode;
import org.apache.commons.jexl2.parser.ASTNotNode;
import org.apache.commons.jexl2.parser.ASTReference;
import org.apache.commons.jexl2.parser.ASTReferenceExpression;
import org.apache.commons.jexl2.parser.JexlNode;
import org.apache.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Class to check that each query node contains a field which exists in the schema.
 *
 * <pre>
 * 1. If a datatype filter was specified, then the existence check is limited to only those datatypes.
 * 2. If a datatype filter is NOT specified (null), this implies ALL datatypes.
 * 3. If a datatype filter is specified but not populated (empty), this implies NO datatypes.
 * </pre>
 */
public class FieldMissingFromSchemaVisitor extends ShortCircuitBaseVisitor {

    private static final Logger log = Logger.getLogger(FieldMissingFromSchemaVisitor.class);

    private final MetadataHelper helper;
    private final Set<String> allFieldsForDatatypes; // All fields for the specified datatypes pulled from MetadataHelper
    private final Set<String> specialFields;
    private final Set<String> datatypeFilter;

    public FieldMissingFromSchemaVisitor(MetadataHelper helper, Set<String> datatypeFilter, Set<String> specialFields) {
        this.helper = helper;
        this.specialFields = specialFields;
        try {
            this.allFieldsForDatatypes = this.helper.getAllFields(datatypeFilter);
        } catch (TableNotFoundException e) {
            log.error(e);
            throw new RuntimeException("Unable to get metadata", e);
        }
        this.datatypeFilter = datatypeFilter;
    }

    @SuppressWarnings("unchecked")
    public static Set<String> getNonExistentFields(MetadataHelper helper, ASTJexlScript script, Set<String> datatypes, Set<String> specialFields) {
        FieldMissingFromSchemaVisitor visitor = new FieldMissingFromSchemaVisitor(helper, datatypes, specialFields);
        return (Set<String>) script.jjtAccept(visitor, new HashSet<>());
    }

    /**
     * @param node Jexl node
     * @param data The set of names which we have determined do not exist
     * @return the updated set of names which do not exist
     */
    protected Object genericVisit(JexlNode node, Object data) {
        @SuppressWarnings("unchecked")
        Set<String> nonExistentFieldNames = (null == data) ? new HashSet<>() : (Set<String>) data;
        List<ASTIdentifier> identifiers;

        // A node could be literal == literal in terms of an identityQuery
        try {
            identifiers = JexlASTHelper.getIdentifiers(node);
        } catch (NoSuchElementException e) {
            return nonExistentFieldNames;
        }

        if (identifiers.isEmpty()) {
            // Catch cases where we have two literals
            // essentially everything but identifier op literal
            return nonExistentFieldNames;
        }

        for (ASTIdentifier identifier : identifiers) {
            String fieldName = JexlASTHelper.deconstructIdentifier(identifier);
            addField(fieldName, fieldName, nonExistentFieldNames);
        }
        return nonExistentFieldNames;
    }

    private void addField(String fieldToAdd, String specialFieldsField, Set<String> fields) {
        if (!this.allFieldsForDatatypes.contains(fieldToAdd) && !specialFields.contains(specialFieldsField)) {
            fields.add(fieldToAdd);
        }
    }

    @Override
    public Object visit(ASTERNode node, Object data) {
        return genericVisit(node, data);
    }

    @Override
    public Object visit(ASTNRNode node, Object data) {
        return genericVisit(node, data);
    }

    @Override
    public Object visit(ASTEQNode node, Object data) {
        return genericVisit(node, data);
    }

    @Override
    public Object visit(ASTNENode node, Object data) {
        return genericVisit(node, data);
    }

    @Override
    public Object visit(ASTGENode node, Object data) {
        return genericVisit(node, data);
    }

    @Override
    public Object visit(ASTGTNode node, Object data) {
        return genericVisit(node, data);
    }

    @Override
    public Object visit(ASTLENode node, Object data) {
        return genericVisit(node, data);
    }

    @Override
    public Object visit(ASTLTNode node, Object data) {
        return genericVisit(node, data);
    }

    @Override
    public Object visit(ASTFunctionNode node, Object data) {
        JexlArgumentDescriptor desc = JexlFunctionArgumentDescriptorFactory.F.getArgumentDescriptor(node);
        @SuppressWarnings("unchecked")
        Set<String> nonExistentFieldNames = (null == data) ? new HashSet<>() : (Set<String>) data;

        try {
            Set<String> fields = desc.fields(this.helper, this.datatypeFilter);
            if (!fields.isEmpty()) {
                for (String fieldName : fields) {
                    // deconstruct the identifier
                    final String testFieldName = JexlASTHelper.deconstructIdentifier(fieldName);
                    // changed to allow _ANYFIELD_ in functions
                    addField(testFieldName, fieldName, nonExistentFieldNames);
                }
            } else {
                genericVisit(node, data);
            }
        } catch (TableNotFoundException e) {
            QueryException qe = new QueryException(DatawaveErrorCode.METADATA_TABLE_FETCH_ERROR, e);
            log.error(qe);
            throw new DatawaveFatalQueryException(qe);
        } catch (InstantiationException | IllegalAccessException e) {
            QueryException qe = new QueryException(DatawaveErrorCode.METADATA_TABLE_RECORD_FETCH_ERROR, e);
            log.error(qe);
            throw new DatawaveFatalQueryException(qe);
        } catch (ExecutionException | MarkingFunctions.Exception e) {
            QueryException qe = new QueryException(DatawaveErrorCode.UNKNOWN_SERVER_ERROR, e);
            log.error(qe);
            throw new DatawaveFatalQueryException(qe);
        }

        return nonExistentFieldNames;
    }

    // Descend through these nodes
    @Override
    public Object visit(ASTJexlScript node, Object data) {
        node.childrenAccept(this, data);
        return data;
    }

    @Override
    public Object visit(ASTAndNode node, Object data) {
        node.childrenAccept(this, data);
        return data;
    }

    @Override
    public Object visit(ASTNotNode node, Object data) {
        node.childrenAccept(this, data);
        return data;
    }

    @Override
    public Object visit(ASTReference node, Object data) {
        node.childrenAccept(this, data);
        return data;
    }

    @Override
    public Object visit(ASTReferenceExpression node, Object data) {
        node.childrenAccept(this, data);
        return data;
    }

}
