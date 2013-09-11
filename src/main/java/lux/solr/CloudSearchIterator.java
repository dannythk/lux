package lux.solr;

import lux.Evaluator;
import lux.SearchIteratorBase;
import lux.exception.LuxException;
import lux.functions.SearchBase.QueryParser;
import lux.index.FieldName;
import lux.index.IndexConfiguration;
import lux.index.field.IDField;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.SequenceIterator;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.trans.XPathException;

import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.SortSpec;

public class CloudSearchIterator extends SearchIteratorBase {
    
    private int limit; // = solr 'rows'
    private SolrQueryResponse response;
    private final String query;
    private final QueryParser queryParser;
    private final String xmlFieldName;
    private final String uriFieldName;
    private final String idFieldName;
    
    /**
     * Initialize the iterator
     * @param eval the Evaluator holds context for the query
     * @param query the Lucene query to execute and iterate
     * @param queryParser either blank (for the default qp), or 'xml' for the xml query parser  TODO: enum
     * @param sortCriteria the sort order for the results
     * @param start1 the 1-based start position at which to begin the iteration
     */
    public CloudSearchIterator (Evaluator eval, String query, QueryParser queryParser, String sortCriteria, int start1) {
        super (eval, sortCriteria, start1);
        this.limit = 20;
        this.queryParser = queryParser;
        this.query = query;
        IndexConfiguration indexConfig = eval.getCompiler().getIndexConfiguration();
        this.xmlFieldName = indexConfig.getFieldName(FieldName.XML_STORE);
        this.uriFieldName = indexConfig.getFieldName(FieldName.URI);
        this.idFieldName = indexConfig.getFieldName(IDField.getInstance());
    }

    @Override
    public SequenceIterator<NodeInfo> getAnother() throws XPathException {
        return new CloudSearchIterator(eval, query, queryParser, sortCriteria, start + 1);
    }

    @Override
    public NodeInfo next() throws XPathException {
        for (;;) {
            if (response != null) {
                SolrDocumentList docs = (SolrDocumentList) response.getValues().get("response");
                if (docs == null) {
                    return null;
                }
                // FIXME: test pagination I think there is a bug here if w/start > 0?
                if (position < docs.getStart() + docs.size()) {
                    SolrDocument doc = docs.get(position++);
                    String uri = (String) doc.getFirstValue(uriFieldName);
                    Object oxml = doc.getFirstValue(xmlFieldName);
                    Long id = (Long) doc.getFirstValue(idFieldName);
                    if (id == null) {
                        // try to support migrating an old index?
                        throw new LuxException("This index has no lux docids: it cannot support Lux on Solr Cloud");
                    }
                    String xml = (String) ((oxml instanceof String) ? oxml : null);
                    byte [] bytes = (byte[]) ((oxml instanceof byte[]) ? oxml : null);
                    XdmNode node = eval.getDocReader().createXdmNode(id, uri, xml, bytes);
                    return node.getUnderlyingNode();
                } else if (position >= docs.getNumFound()) {
                    return null;
                }
            }
            doCloudSearch();
        }
    }
    
    /* Make a new query request, using this.query, start calculated based on the passed-in responseBuilder
    sorting based on sortCriteria, and fields=lux_xml.  Also: if rb asks for debug, pass that along
    */
    private void doCloudSearch () {
        ResponseBuilder origRB = ((SolrQueryContext)eval.getQueryContext()).getResponseBuilder();
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.add((CommonParams.Q), query);
        if (QueryParser.XML == queryParser) {
            params.add("defType", "xml");
        }
        params.add(CommonParams.START, Integer.toString(position));
        params.add(CommonParams.ROWS, Integer.toString(limit));
        params.add(CommonParams.FL, uriFieldName, xmlFieldName, idFieldName);

        SolrParams origParams = origRB.req.getParams();
        String debug = origParams.get(CommonParams.DEBUG);
        if (debug != null) {
            params.add(CommonParams.DEBUG, debug);
        }
        params.add("distrib", "true");
        params.add("shards", origParams.get("shards"));
        SolrQueryRequest req = new CloudQueryRequest(origRB.req.getCore(), params, makeSortSpec());
        XQueryComponent xqueryComponent = ((SolrQueryContext)eval.getQueryContext()).getQueryComponent();
        response = new SolrQueryResponse();
        xqueryComponent.getSearchHandler().handleRequest(req, response);
        // defensive...
        SolrDocumentList docs = (SolrDocumentList) response.getValues().get("response");
        if (docs != null) {
            eval.getQueryStats().docCount += docs.getNumFound();
        }
    }
    
    private SortSpec makeSortSpec () {
        Sort sort;
        if (sortCriteria != null) {
            sort = makeSortFromCriteria();
        } else {
            sort = new Sort (SortField.FIELD_SCORE);
        }
        return new SortSpec (sort, start, limit);
    }
    
}
