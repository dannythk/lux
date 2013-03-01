package lux.solr;

import org.apache.lucene.document.Document;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.update.AddUpdateCommand;

public class UpdateDocCommand extends AddUpdateCommand {
    
    private Document doc;

    public UpdateDocCommand(SolrCore core, Document doc) {
        super(makeSolrRequest(core));
        this.doc = doc;
        // TODO: create SolrDoc containing unique key value (for hashing)
        /*
        overwriteCommitted = true;
        overwritePending = true;
        allowDups = false;
        */
    }
    
    public Document getLuceneDocument () {
        return doc;
    }

    private static SolrQueryRequest makeSolrRequest(SolrCore core) {
        SolrParams params = new ModifiableSolrParams () {};
        return new SolrQueryRequestBase(core, params) {};
    }

}
