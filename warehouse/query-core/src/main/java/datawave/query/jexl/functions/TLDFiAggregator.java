package datawave.query.jexl.functions;

import datawave.query.Constants;
import datawave.query.attributes.Attribute;
import datawave.query.attributes.AttributeFactory;
import datawave.query.attributes.Document;
import datawave.query.data.parsers.FieldIndexKey;
import datawave.query.data.parsers.KeyParser;
import datawave.query.jexl.JexlASTHelper;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.hadoop.io.Text;

import java.io.IOException;

/**
 * A field index aggregator for TLD queries
 */
public class TLDFiAggregator extends FiAggregator {

    public TLDFiAggregator(){
        //  empty constructor
    }

    //  apply(SortedKeyValueIterator<Key,Value> itr)
    //  this path is handled by overriding sameUid()

    //  apply(SortedKeyValueIterator<Key,Value> iter, Range range, Collection<ByteSequence> cfs, boolean includeColumnFamilies)
    //  this path is handled by overriding getSeekRange()

    @Override
    public Key apply(SortedKeyValueIterator<Key,Value> itr, Document d, AttributeFactory af) throws IOException {
        Key key = itr.getTopKey();
        tkParser.parse(key);
        Key k = getParentKey(tkParser);

        Key tk = key;
        while (tk != null) {
            parser.parse(tk);

            if (!sameUid(tkParser, parser)) {
                break;
            }

            Attribute<?> attr = af.create(parser.getField(), parser.getValue(), tk, true);
            // in addition to keeping fields that the filter indicates should be kept, also keep fields that the filter applies. This is due to inconsistent
            // behavior between event/tld queries where an index only field index will be kept except when it is a child of a tld

            boolean fieldKeep = fieldsToKeep == null || fieldsToKeep.contains(JexlASTHelper.removeGroupingContext(parser.getField()));
            boolean filterKeep = filter == null || filter.keep(tk);
            boolean toKeep = fieldKeep && filterKeep;
            attr.setToKeep(toKeep);
            d.put(parser.getField(), attr);

            //  if the child id does not equal the parent id, add a new document key
            if (!tkParser.getUid().equals(parser.getUid()) || parser.getRootUid().equals(parser.getUid())) {
                d.put(Document.DOCKEY_FIELD_NAME, getRecordId(parser));
            }

            //  all non-event fields are aggregated into a document
            //  in certain cases an aggregator could aggregate a single value and then
            //  skip to the next document
            //  ======
            //  for example: an equality term that is not part of an evaluation phase function

            itr.next();
            tk = itr.hasTop() ? itr.getTopKey() : null;
        }
        return k;
    }

    //  helper methods

    @Override
    protected boolean sameUid(KeyParser parser, KeyParser other) {
        return parser.getRootUid().equals(other.getRootUid());
    }

    @Override
    protected Range getSeekRange(FieldIndexKey parser, Range range){
        Key k = parser.getKey();
        Text cq = new Text(parser.getValue() + '\u0000' + parser.getDatatype() + '\u0000' + parser.getRootUid() + Constants.MAX_UNICODE_STRING);
        Key startKey = new Key(k.getRow(), k.getColumnFamily(), cq, 0);
        return new Range(startKey, false, range.getEndKey(), range.isEndKeyInclusive());
    }
}
