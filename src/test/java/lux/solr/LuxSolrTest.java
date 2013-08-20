package lux.solr;


import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.TimeZone;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.junit.BeforeClass;
import org.junit.Test;

public class LuxSolrTest extends BaseSolrTest {
    
    private static final String XML_TEXT = "lux_text";
    private static final String LUX_PATH = "lux_path";
    private static final String LUX_ELT_TEXT = "lux_elt_text";
    private static final String LUX_ATT_TEXT = "lux_att_text";

    @BeforeClass
    public static void setup () throws Exception {
        BaseSolrTest.setup();
        Collection<SolrInputDocument> docs = new ArrayList<SolrInputDocument> ();
        addSolrDocFromFile("src/test/resources/conf/schema.xml", docs);
        addSolrDocFromFile("src/test/resources/conf/solrconfig.xml", docs);
        for (int i = 1; i <= 100; i++) {
            addSolrDoc ("test" + i, "<doc><title id='" + i + "'>" + (101-i) + "</title><test>cat</test></doc>", docs);
        }
        solr.add (docs);
        solr.commit();
    }
    
    @Test public void testIndex() throws Exception {
        // make sure the documents have the values we expect
        assertQueryCount (102, "*:*");
        // QNAME index is no longer part of the default setup created by LuxUpdateProcessor
        //assertQueryCount (1, XmlIndexer.ELT_QNAME.getName() + ":config");
        //assertQueryCount (1, XmlIndexer.ELT_QNAME.getName() + ":schema");
        assertQueryCount (1, LUX_PATH + ":\"{} schema types fieldType\"");
        assertQueryCount (1, LUX_PATH + ":schema");
        assertQueryCount (102, LUX_PATH + ":\"{}\"");
        assertQueryCount (1, LUX_PATH + ":\"{} config luceneMatchVersion\"");
        assertQueryCount (2, XML_TEXT + ":true");
        assertQueryCount (2, LUX_ELT_TEXT + ":enableLazyFieldLoading\\:true");
        assertQueryCount (1, LUX_ATT_TEXT + ":id\\:1");
        assertQueryCount (1, LUX_ATT_TEXT + ":type\\:random");
    }
    
    @Test public void testXPathSearch() throws Exception {
        // test search using standard search query handler, custom query parser
        assertXPathSearchCount (1, 1, "element", "config", "//config");
        assertXPathSearchCount (34, 1, 50, "element", "abortOnConfigurationError", "/config/*");
    }
    
    @Test public void testAtomicResult () throws Exception {
        // This also tests lazy evaluation - like paging within xpath.  Because we only retrieve
        // the first value (in document order), we only need to retrieve one value.
        assertXPathSearchCount (1, 1, "xs:double", "100.0", "number((/doc/title)[1])");
    }
    
    @Test public void testLiteral () throws Exception {
        // no documents were retrieved, 1 result returned = 12
        assertXPathSearchCount(1, 0, "xs:double", "12.0", "xs:double(12.0)");
    }
    
    @Test public void testFirstPage () throws Exception {
        // returns only the page including the first 10 results
        assertXPathSearchCount (10, 100, "document", "doc", "(/)[doc]");
        
        assertXPathSearchCount (10, 100, "element", "doc", "(//doc)[position() > 10]");
    }
    
    @Test public void testPaging () throws Exception {
        // make the searcher page past the first 10 documents to find 10 xpath matches
        assertXPathSearchCount (10, 100, "element", "doc", "//doc[title[number(.) < 95]]");
    }
    
    /**
     * This test confirms that fields declared in solrconfig.xml/schema.xml are indexed
     * and made available for sorting, as well as exercising sorting optimization and the 
     * string sorting implementations
     * @throws Exception 
     */
    @Test public void testSorting () throws Exception {
        // should be 1, 10, 100, 11, 12, ..., 2, 21, 22, ...
        // which is docs 101, 92, 2, (since there are 2 docs with no title that are loaded first)
        assertXPathSearchCount(1, 5, "xs:string", "1,10,100,11,12", "string-join(subsequence((for $doc in //doc order by $doc/lux:key('title') return $doc/title/string()),1,5),',')");
        assertXPathSearchCount(1, 1, "xs:string", "1", "(for $doc in //doc order by $doc/lux:key('title') return $doc/title/string())[1]");
        assertXPathSearchCount(1, 1, "xs:string", "99", "(for $doc in //doc order by $doc/lux:key('title') descending return $doc/title/string())[1]");
        assertXPathSearchCount(1, 2, "xs:string", "10", "(for $doc in //doc order by $doc/lux:key('title') return $doc/title/string())[2]");
        // test providing the sort criteria directly to lux:search()
        assertXPathSearchCount(1, 2, "xs:string", "10", "(for $doc in lux:search('<test:cat', 'title') return $doc/doc/title/string())[2]");
        // TODO: implement wildcard element query to test for existence of some element
        // assertXPathSearchCount(1, 2, "xs:string", "10", "lux:search('<doc:*', (), 'title')[2]");
    }
    
    @Test public void testDocFunction () throws Exception {
        assertXPathSearchCount (1, 0, "document", "doc", "doc('test50')");
        // TODO: eliminate the repetition of the error:
        assertXPathSearchCount (0, 0, "error", "document not found: /foo\ndocument not found: /foo\n", "doc('/foo')");  
    }
    
    @Test public void testCollectionFunction () throws Exception {
        assertXPathSearchCount (1, 1, "xs:anyURI", "lux:/src/test/resources/conf/schema.xml", "collection()[1]/base-uri()");
        assertXPathSearchCount (1, 102, "xs:anyURI", "lux:/test100", "collection()[last()]/base-uri()");
        assertXPathSearchCount (1, 102, "xs:integer", "102", "count(collection())");
    }
    
    @Test public void testQueryError () throws Exception {
        assertXPathSearchError("Prefix lux_elt_name_ms has not been declared; Line#: 1; Column#: 22\n", "lux_elt_name_ms:config");
    }
    
    @Test
    public void testSyntaxError () throws Exception {
        assertXPathSearchError("Unexpected token name \"bad\" beyond end of query; Line#: 1; Column#: 4\n", "hey bad boy");
    }
    
    @Test
    public void testCreateCore () throws Exception {
        SolrQuery q = new SolrQuery();
        q.setRequestHandler(coreContainer.getAdminPath());
        q.setParam ("action", "CREATE");
        q.setParam ("name", "core2");
        q.setParam ("instanceDir", "core2");
        solr.query(q);
        SolrServer core2 = new EmbeddedSolrServer(coreContainer, "core2");
        core2.deleteByQuery("*:*");
        core2.commit();
        assertQueryCount (0, "*:*", core2);
        // main core still works
        assertXPathSearchCount (1, 102, "xs:integer", "102", "count(collection())", solr);
        // new core working too
        assertXPathSearchCount(1, 0, "xs:integer", "0", "count(collection())", core2);
    }
    
    @Test
    public void testAppServer () throws Exception {
        SolrQuery q = new SolrQuery();
        q.setRequestHandler("/testapp");
    	q.setParam("test-param", "test-value");
    	q.setParam("wt", "lux");
    	q.setParam("lux.contentType", "text/xml");
    	QueryResponse resp = solr.query(q);
    	assertEquals ("query was blank", resp.getResponse().get("xpath-error"));
    	q.setParam("lux.xquery", "lux/solr/echo.xqy");
    	resp = solr.query(q);
    	NamedList<?> xpathResults = (NamedList<?>) resp.getResponse().get("xpath-results"); 
    	assertEquals (
    			"<http><params>" +
    			"<param name=\"wt\"><value>lux</value></param>" +
    			"<param name=\"qt\"><value>/testapp</value></param>" +
    			"<param name=\"test-param\"><value>test-value</value></param>" +
    			"<param name=\"wt\"><value>lux</value></param></params><context-path/></http>", 
    			xpathResults.get("document").toString());
    	assertTrue(resp.getResults().isEmpty());
    }
    
    @Test
    public void testPrimitiveValues () throws Exception {
        assertQuery (true, "xs:boolean", "true()");
        assertQuery (false, "xs:boolean", "false()");
        
        assertQuery ("{local}name", "xs:QName", "fn:QName('local','l:name')");
        
        assertQuery (null, null, "()");
        assertQuery ("x", "xs:untypedAtomic", "xs:untypedAtomic('x')");
        
        assertQuery (1L, "xs:integer", "xs:integer(1)");
        assertQuery (1L, "xs:integer", "1");
        assertQuery (1L, "xs:int", "xs:int(1)");
        assertQuery (1.0, "xs:decimal", "1.0");
        assertQuery (1.0, "xs:double", "xs:double(1.0)");
        assertQuery (1.0f, "xs:float", "xs:float(1.0)");
        assertQuery ("1", "xs:string", "'1'");
        assertQuery ("1", "xs:string", "xs:anyURI('1')");
        
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(2013,3,6,0,0,0);
        cal.set(Calendar.MILLISECOND, 0);
        assertQuery (cal.getTime(), "xs:date", "xs:date('2013-04-06')");
        assertQuery (cal.getTime(), "xs:dateTime", "xs:dateTime('2013-04-06T00:00:00')");
        assertQuery ("00:00:00", "xs:time", "xs:time('00:00:00')");
        
        assertQuery ("0900", "xs:gYear", "xs:gYear('0900')");
        assertQuery ("--11", "xs:gMonth", "xs:gMonth('--11')");
        assertQuery ("2012-12", "xs:gYearMonth", "xs:gYearMonth('2012-12')");
        assertQuery ("---01", "xs:gDay", "xs:gDay('---01')");
        assertQuery ("--12-01", "xs:gMonthDay", "xs:gMonthDay('--12-01')");
        
    }

}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
