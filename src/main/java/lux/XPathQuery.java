package lux;

import java.io.IOException;
import java.util.Set;

import lux.api.Expression;
import lux.api.LuxException;
import lux.api.ValueType;
import lux.index.XmlIndexer;
import lux.lucene.SurroundBoolean;
import lux.lucene.SurroundMatchAll;
import lux.lucene.SurroundSpanQuery;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Weight;

/**
 * Wraps a Lucene Query, with advice as to how to process its results as XPath.
 * For now, simply distinguishes the two cases: whether the results are in fact
 * supposed to be the results of the original XPath evaluation, or if further
 * evaluation is needed.
 * 
 * We could also tell: whether the query will return the correct document set;
 * it's possible that we may sometimes retrieve documents that don't match.
 * We're not allowed to miss a document, though. Some evaluators that return the
 * correct doc set still may need additional evaluation though if the results
 * are not to be documents.
 */
public class XPathQuery extends Query {

    private final Query query;
    private Expression expr;
    private ValueType valueType;
    private boolean immutable;
    
    public boolean isImmutable() {
        return immutable;
    }

    /** bitmask holding facts proven about the query; generally these facts enable different
     * optimizations.  In the comments, we refer to the "result type" of the query meaning the
     * result type of the xpath expression that the query was generated from.
     */
    protected long facts;
    
    /**
     * A query is exact iff its xpath expression returns exactly one value per document, and the
     * generated lucene query returns exactly those documents satisfying the xpath expression.
     */
    public static final int EXACT=0x00000001;
    
    /**
     * A query is minimal if it returns all, and only, those documents satisfying the xpath expression.
     * Exact queries are all minimal.
     */
    public static final int MINIMAL=0x00000002;
    
    /**
     * A query is counting if its expression returns the count of the results of the lucene query
     */
    public static final int COUNTING=0x00000004;
    
    public static final int RESULT_TYPE_FLAGS = 0x00000018;

    /**
     * A query is boolean_true if its result type is boolean, and the existence of a single query result indicates a 'true()' value
     */
    public static final int BOOLEAN_TRUE=0x00000008;

    /**
     * A query is boolean_false if its result type is boolean, and the existence of a single query result indicates a 'false()' value
     */
    public static final int BOOLEAN_FALSE=0x00000010;
    
    /**
     * A query has document results if its result type is document-node()
     */
    public static final int DOCUMENT_RESULTS=0x00000018;
    
    // TODO -- represent not() and exists() using count() > 0
    
    /**
     * @param expr an XPath 2.0 expression
     * @param query a Lucene query
     * @param resultFacts a bitmask with interesting facts about this query
     * @param valueType the type of results returned by the xpath expression, as specifically as 
     * can be determined.
     */
    protected XPathQuery(Expression expr, Query query, long resultFacts, ValueType valueType) {
        this.expr = expr;
        this.query = query;
        this.facts = resultFacts;
        setType (valueType);
    }
    
    public static XPathQuery getQuery (Query query, long resultFacts, ValueType valueType, long options) {
        if (query instanceof MatchAllDocsQuery && resultFacts == MINIMAL) {
            if (valueType == ValueType.DOCUMENT) {
                if ((options & XmlIndexer.INDEX_PATHS) != 0) {
                    return PATH_MATCH_ALL;
                }
                return MATCH_ALL;
            }
            if (valueType == ValueType.NODE) {
                if ((options & XmlIndexer.INDEX_PATHS) != 0) {
                    return PATH_MATCH_ALL_NODE;
                }
                return MATCH_ALL_NODE;
            }
        }
        return new XPathQuery (null, query, resultFacts, valueType);
    }
    
    public static XPathQuery getQuery (Query query, long resultFacts, long options) {
        return getQuery (query, resultFacts, typeFromFacts(resultFacts), options);
    }
    
    public static XPathQuery getMatchAllQuery (long options) {
        if ((options & XmlIndexer.INDEX_PATHS) != 0) {
            return PATH_MATCH_ALL;
        }
        return MATCH_ALL;
    }
    
    public static ValueType typeFromFacts (long facts) {
        long typecode = (facts & XPathQuery.RESULT_TYPE_FLAGS); 
        if (typecode == XPathQuery.BOOLEAN_FALSE) {
            return ValueType.BOOLEAN_FALSE;
        } else if (typecode == XPathQuery.BOOLEAN_TRUE) {
            return ValueType.BOOLEAN;
        } else if (typecode == XPathQuery.DOCUMENT_RESULTS) {
            return ValueType.DOCUMENT;
        } 
        return ValueType.VALUE;                
    }
    
    public Query getQuery() {
        return query;
    }
    
    public Expression getExpression() {
        return expr;
    }

    /**
     * @return whether it is known that the query will return the minimal set of
     *         documents containing the required result value. If false, some
     *         documents may be returned that will eventually need to be
     *         discarded if they don't match the xpath.
     */
    public boolean isMinimal() {
        return (facts & MINIMAL) != 0;
    }
    
    /**
     * @return whether the query is minimal and the xpath expression is single-valued.
     */
    public boolean isExact() {
        return (facts & EXACT) != 0;
    }

    public ValueType getResultType() {
        return valueType;
    }

    public final static XPathQuery MATCH_ALL = new XPathQuery(null, new MatchAllDocsQuery(), MINIMAL, ValueType.DOCUMENT);
    
    public final static XPathQuery MATCH_ALL_NODE = new XPathQuery(null, new MatchAllDocsQuery(), MINIMAL, ValueType.NODE);

    public final static XPathQuery UNINDEXED = new XPathQuery(null, new MatchAllDocsQuery(), 0, ValueType.VALUE);

    public final static XPathQuery PATH_MATCH_ALL = new XPathQuery(null, SurroundMatchAll.getInstance(), MINIMAL, ValueType.DOCUMENT);
    
    public final static XPathQuery PATH_MATCH_ALL_NODE = new XPathQuery(null, SurroundMatchAll.getInstance(), MINIMAL, ValueType.NODE);

    public final static XPathQuery PATH_UNINDEXED = new XPathQuery(null, SurroundMatchAll.getInstance(), 0, ValueType.VALUE);

    // TODO: merge w/constructor and make immutable final
    static {
        MATCH_ALL.immutable = true;
        UNINDEXED.immutable = true;
        MATCH_ALL_NODE.immutable = true;
        PATH_MATCH_ALL.immutable = true;
        PATH_UNINDEXED.immutable = true;
        PATH_MATCH_ALL_NODE.immutable = true;
    }

    /**
     * Combines this query with another according to the logic of occur. The
     * valueType of the resulting query will be the same as that of this query
     * if occur is AND (or NOT). If occur is OR, the valueType is the most
     * specific type that includes the types of each query. The combined query
     * is minimal iff both queries are.
     * 
     * @param precursor
     *            the query to combine with this; precursor since it corresponds
     *            to the preceding expr's query.
     * @param occur
     *            whether the two queries MUST occur (this AND precursor) or MAY
     *            occur (this OR precursor).
     * @return the combined query
     */
    public XPathQuery combine(XPathQuery precursor, Occur occur) {
        ValueType combinedType = occur == Occur.SHOULD ? valueType.promote(precursor.valueType) : this.valueType;
        return combine(occur, precursor, occur, combinedType);
    }

    /**
     * Combines this query with another, while specifying a valueType
     * restriction for the resultant query's results.
     */
    public XPathQuery combine(Occur occur, XPathQuery precursor, Occur precursorOccur, ValueType type) {
        long resultFacts = combineQueryFacts (this, precursor);
        Query result = combineBoolean (this.query, occur, precursor.query, precursorOccur);
        return getQuery(result, resultFacts, type, 0);
    }

    /**
     * Combines this query with another, while specifying a valueType
     * restriction for the resultant query's results, and an allowable
     * distance between the two queries.  Generates Lucene SpanQuerys, and
     * the constituent queries must be span queries as well.
     */
    public XPathQuery combine(XPathQuery precursor, Occur occur, ValueType type, int distance) {
        long resultFacts = combineQueryFacts (this, precursor);
        Query result = combineSpans (this.query, occur, precursor.query, distance);
        return new XPathQuery(expr, result, resultFacts, type);
    }

    private static long combineQueryFacts (XPathQuery a, XPathQuery b) {
        if (b.isEmpty()) {
            return a.facts; 
        }
        else if (a.isEmpty()) {
            return b.facts;
        }
        else {
            return combineFacts(a.facts, b.facts);
        }
    }

    private static Query combineBoolean (Query a, Occur aOccur, Query b, Occur bOccur) {
        BooleanQuery bq = new BooleanQuery();
        if (a instanceof MatchAllDocsQuery) {
            if (bOccur != Occur.MUST_NOT) {
                return b;
            }
        } else {
            bq.add(new BooleanClause(a, aOccur));
        }
        if (b instanceof MatchAllDocsQuery) {
            if (aOccur != Occur.MUST_NOT) {
                return a;
            }
        } else {
            bq.add(new BooleanClause(b, bOccur));
        }
        return bq;
    }
    
    private static Query combineSpans (Query a, Occur occur, Query b, int distance) {
        if (distance == 0) {
            return new SurroundSpanQuery(distance, true, occur, a, b);
        }
        // don't create a span query for //foo; a single term is enough
        if (a instanceof SurroundMatchAll && occur != Occur.MUST_NOT) {
            return b;
        }
        if (b instanceof SurroundMatchAll) {
            return a;
        }
        if (distance > 1) {
            return new SurroundSpanQuery(distance, true, occur, a, b);
        }
        return new SurroundBoolean (occur, a, b);
    }
    
    private static final long combineFacts (long facts2, long facts3) {
        return facts2 & facts3;
    }

    /**
     * Set this query's result type to be the least restrictive type encompassing its type and the given type
     * @param valueType the type to restrict to
     */
    public void restrictType(ValueType type) {
        if (immutable) throw new LuxException ("attempt to modify immutable query");
        valueType = valueType.restrict(type);
    }

    public boolean isEmpty() {
        return this == MATCH_ALL || this == UNINDEXED || this == MATCH_ALL_NODE;
    }
    
    public String toString () {
        return query == null ? "" : query.toString();
    }

    /* 
     * Wrapped methods from org.apache.lucene.search.Query
     */

    @Override
    public String toString(String field) {
       return query.toString(field);
    }

    @SuppressWarnings("deprecation")
    public Weight createWeight(org.apache.lucene.search.Searcher searcher) throws IOException {
        return query.createWeight (searcher);
    }

    public Query rewrite(IndexReader reader) throws IOException {
        Query rq = query.rewrite (reader);
        if (rq == null) {
            System.err.println ("query.rewrite returned null for: " + this);
        }
        return rq;
    }

    // return an XPathQuery??
    public Query combine(Query[] queries) {
        return query.combine (queries);
    }

    public void extractTerms(Set<Term> terms) {
        query.extractTerms (terms);
    }

    /** Returns a hash code value for this object.*/
    @Override public int hashCode() {
        return query.hashCode();
    }

  /** Returns true iff <code>o</code> is equal to this. */
  @Override
  public boolean equals(Object o) {
    if (!(o instanceof XPathQuery))
      return false;
    XPathQuery other = (XPathQuery)o;
    return (expr == null ? (other.expr == null) : expr.equals (other.expr)) &&
            super.equals (o);
  }

  public void setFact(int fact, boolean t) {
      if (immutable) throw new LuxException ("attempt to modify immutable query");
      if (t) {
          facts |= fact;
      } else {
          facts &= (~fact);
      }
  }
  
  public final boolean isFact (int fact) {
      return (facts & fact) == fact;
  }

  public long getFacts() {
      return facts;
  }

  public void setType(ValueType type) {
      if (immutable) throw new LuxException ("attempt to modify immutable query");
      valueType = type;
      facts &= (~RESULT_TYPE_FLAGS);
      if (valueType == ValueType.BOOLEAN) {
          facts |= BOOLEAN_TRUE;         
      }
      else if (valueType == ValueType.BOOLEAN_FALSE) {
          facts |= BOOLEAN_FALSE;
      }
      else if (valueType == ValueType.DOCUMENT) {
          facts |= DOCUMENT_RESULTS;
      }
      // no other type info is stored in facts since it's not needed by search()
  }
  
  public void setExpression (Expression expr) {
      if (immutable) throw new LuxException ("attempt to modify immutable query");
      this.expr = expr;
  }
  
}