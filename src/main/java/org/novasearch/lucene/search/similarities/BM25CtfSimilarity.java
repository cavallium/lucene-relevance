/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.novasearch.lucene.search.similarities;


import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.SmallFloat;

/**
 * BM25 Similarity. Introduced in Stephen E. Robertson, Steve Walker,
 * Susan Jones, Micheline Hancock-Beaulieu, and Mike Gatford. Okapi at TREC-3.
 * In Proceedings of the Third <b>T</b>ext <b>RE</b>trieval <b>C</b>onference (TREC 1994).
 * Gaithersburg, USA, November 1994.
 */
public class BM25CtfSimilarity extends Similarity {
  private final float k1;
  private final float b;
  private final float d;

  /**
   * BM25 with the supplied parameter values.
   * @param k1 Controls non-linear term frequency normalization (saturation).
   * @param b Controls to what degree document length normalizes tf values.
   * @param d Controls lower-bound of term frequency normalization in BM25.
   * @throws IllegalArgumentException if {@code k1} is infinite or negative, or if {@code b} is 
   *         not within the range {@code [0..1]}
   */
  public BM25CtfSimilarity(float k1, float b, float d) {
    if (Float.isFinite(k1) == false || k1 < 0) {
      throw new IllegalArgumentException("illegal k1 value: " + k1 + ", must be a non-negative finite value");
    }
    if (Float.isNaN(b) || b < 0 || b > 1) {
      throw new IllegalArgumentException("illegal b value: " + b + ", must be between 0 and 1");
    }
    if (Float.isNaN(d) || d < 0 || d > 1) {
      throw new IllegalArgumentException("illegal d value: " + d + ", must be between 0 and 1");
    }
    this.k1 = k1;
    this.b  = b;
    this.d  = d;
  }
  
  /** BM25 with these default values:
   * <ul>
   *   <li>{@code k1 = 1.2}</li>
   *   <li>{@code b = 0.75}</li>
   *   li>{@code d = 0.5}</li>
   * </ul>
   */
  public BM25CtfSimilarity() {
    this(1.2f, 0.75f, 0.5f);
  }
  
  /** Implemented as <code>log(sumTotalTermFreq/totalTermFreq</code>. */
  protected float ictf(long totalTermFreq, long sumTotalTermFreq) {
    return (float) Math.log((float) sumTotalTermFreq/totalTermFreq);
  }
  
  /** Implemented as <code>log((docCount+1)/(docFreq+1)) + 1</code>. */
  protected float idf(long docFreq, long docCount) {
    return (float)(Math.log((docCount+1)/(double)(docFreq+1)) + 1.0);
  }
  
  /** Implemented as code>log(1 + p_df/p_ctf)</code>. */
  protected float pidf(long docFreq, long docCount, long totalTermFreq) {
    float pdf = docCount * (1 - 1 / (float) Math.exp((float) totalTermFreq/docCount));
    float pctf = -docCount * (float) Math.log(1 - (float) docFreq/docCount);
    return (float) Math.log(1 + pdf/pctf);
  }
  
  /** The default implementation returns <code>1</code> */
  protected float scorePayload(int doc, int start, int end, BytesRef payload) {
    return 1;
  }
  
  /** The default implementation computes the average as <code>sumTotalTermFreq / docCount</code> */
  protected float avgFieldLength(CollectionStatistics collectionStats) {
    return (float) (collectionStats.sumTotalTermFreq() / (double) collectionStats.docCount());
  }
  
  /** 
   * True if overlap tokens (tokens with a position of increment of zero) are
   * discounted from the document's length.
   */
  protected boolean discountOverlaps = true;

  /** Sets whether overlap tokens (Tokens with 0 position increment) are 
   *  ignored when computing norm.  By default this is true, meaning overlap
   *  tokens do not count when computing norms. */
  public void setDiscountOverlaps(boolean v) {
    discountOverlaps = v;
  }
  
  /** Cache of decoded bytes. */
  private static final float[] LENGTH_TABLE = new float[256];

  static {
    for (int i = 0; i < 256; i++) {
      LENGTH_TABLE[i] = SmallFloat.byte4ToInt((byte) i);
    }
  }


  @Override
  public final long computeNorm(FieldInvertState state) {
    final int numTerms;
    if (state.getIndexOptions() == IndexOptions.DOCS && state.getIndexCreatedVersionMajor() >= 8) {
      numTerms = state.getUniqueTermCount();
    } else if (discountOverlaps) {
      numTerms = state.getLength() - state.getNumOverlap();
    } else {
      numTerms = state.getLength();
    }
    return SmallFloat.intToByte4(numTerms);
  }

  /**
   * Computes a score factor for a simple term and returns an explanation
   * for that score factor.
   *
   * <p>
   * The default implementation uses:
   *
   * <pre class="prettyprint">
   * idf(docFreq, docCount);
   * </pre>
   *
   * Note that {@link CollectionStatistics#docCount()} is used instead of
   * {@link org.apache.lucene.index.IndexReader#numDocs() IndexReader#numDocs()} because also 
   * {@link TermStatistics#docFreq()} is used, and when the latter 
   * is inaccurate, so is {@link CollectionStatistics#docCount()}, and in the same direction.
   * In addition, {@link CollectionStatistics#docCount()} does not skew when fields are sparse.
   *
   * @param collectionStats collection-level statistics
   * @param termStats term-level statistics for the term
   * @return an Explain object that includes both an idf score factor 
  and an explanation for the term.
   */
  public Explanation ictfExplain(CollectionStatistics collectionStats, TermStatistics termStats) {
    final long ctf = termStats.totalTermFreq();
    final long sumTotalTermFreq = collectionStats.sumTotalTermFreq();
    final float ictf = ictf(ctf, sumTotalTermFreq);
    return Explanation.match(ictf, "ictf, computed as log(M / ctf) from:",
        Explanation.match(sumTotalTermFreq, "M, total number of tokens in the field"),
        Explanation.match(ctf, "ctf, total number of occurrences of this term"));
  }

  /**
   * Computes a score factor for a phrase.
   *
   * <p>
   * The default implementation sums the idf factor for
   * each term in the phrase.
   *
   * @param collectionStats collection-level statistics
   * @param termStats term-level statistics for the terms in the phrase
   * @return an Explain object that includes both an idf 
   *         score factor for the phrase and an explanation 
   *         for each term.
   */
  public Explanation ictfExplain(CollectionStatistics collectionStats, TermStatistics termStats[]) {
    double ictf = 0d; // sum into a double before casting into a float
    List<Explanation> details = new ArrayList<>();
    for (final TermStatistics stat : termStats ) {
      Explanation ictfExplain = ictfExplain(collectionStats, stat);
      details.add(ictfExplain);
      ictf += ictfExplain.getValue().floatValue();
    }
    return Explanation.match((float) ictf, "ictf, sum of:", details);
  }

  /**
   * Computes a score factor for a simple term and returns an explanation
   * for that score factor.
   * 
   * <p>
   * The default implementation uses:
   * 
   * <pre class="prettyprint">
   * ictf(docFreq, docCount);
   * </pre>
   * 
   * Note that {@link CollectionStatistics#docCount()} is used instead of
   * {@link org.apache.lucene.index.IndexReader#numDocs() IndexReader#numDocs()} because also 
   * {@link TermStatistics#docFreq()} is used, and when the latter 
   * is inaccurate, so is {@link CollectionStatistics#docCount()}, and in the same direction.
   * In addition, {@link CollectionStatistics#docCount()} does not skew when fields are sparse.
   *   
   * @param collectionStats collection-level statistics
   * @param termStats term-level statistics for the term
   * @return an Explain object that includes both an idf score factor 
             and an explanation for the term.
   */
  public Explanation idfExplain(CollectionStatistics collectionStats, TermStatistics termStats) {
    final long df = termStats.docFreq();
    final long docCount = collectionStats.docCount();
    final float idf = idf(df, docCount);
    return Explanation.match(idf, "idf, computed as log((docCount+1)/(docFreq+1)) + 1 from:",
        Explanation.match(df, "n, number of documents containing term"),
        Explanation.match(docCount, "N, total number of documents with field"));
  }

  /**
   * Computes a score factor for a phrase.
   * 
   * <p>
   * The default implementation sums the idf factor for
   * each term in the phrase.
   * 
   * @param collectionStats collection-level statistics
   * @param termStats term-level statistics for the terms in the phrase
   * @return an Explain object that includes both an idf 
   *         score factor for the phrase and an explanation 
   *         for each term.
   */
  public Explanation idfExplain(CollectionStatistics collectionStats, TermStatistics termStats[]) {
    double idf = 0d; // sum into a double before casting into a float
    List<Explanation> details = new ArrayList<>();
    for (final TermStatistics stat : termStats ) {
      Explanation idfExplain = idfExplain(collectionStats, stat);
      details.add(idfExplain);
      idf += idfExplain.getValue().floatValue();
    }
    return Explanation.match((float) idf, "idf, sum of:", details);
  }

  /**
   * Computes a score factor for a simple term and returns an explanation
   * for that score factor.
   *
   * <p>
   * The default implementation uses:
   *
   * <pre class="prettyprint">
   * pidf(docFreq, docCount);
   * </pre>
   *
   * Note that {@link CollectionStatistics#docCount()} is used instead of
   * {@link org.apache.lucene.index.IndexReader#numDocs() IndexReader#numDocs()} because also 
   * {@link TermStatistics#docFreq()} is used, and when the latter 
   * is inaccurate, so is {@link CollectionStatistics#docCount()}, and in the same direction.
   * In addition, {@link CollectionStatistics#docCount()} does not skew when fields are sparse.
   *
   * @param collectionStats collection-level statistics
   * @param termStats term-level statistics for the term
   * @return an Explain object that includes both an idf score factor 
  and an explanation for the term.
   */
  public Explanation pidfExplain(CollectionStatistics collectionStats, TermStatistics termStats) {
    final long df = termStats.docFreq();
    final long docCount = collectionStats.docCount();
    final long ctf = termStats.totalTermFreq();
    final float pidf = pidf(df, docCount, ctf);
    return Explanation.match(pidf, "pidf, computed as log(1 + p_df/p_ctf) from:",
        Explanation.match(df, "n, number of documents containing term"),
        Explanation.match(ctf, "ctf, total number of occurrences of this term"),
        Explanation.match(docCount, "N, total number of documents with field"));
  }

  /**
   * Computes a score factor for a phrase.
   *
   * <p>
   * The default implementation sums the idf factor for
   * each term in the phrase.
   *
   * @param collectionStats collection-level statistics
   * @param termStats term-level statistics for the terms in the phrase
   * @return an Explain object that includes both an idf 
   *         score factor for the phrase and an explanation 
   *         for each term.
   */
  public Explanation pidfExplain(CollectionStatistics collectionStats, TermStatistics termStats[]) {
    double pidf = 0d; // sum into a double before casting into a float
    List<Explanation> details = new ArrayList<>();
    for (final TermStatistics stat : termStats ) {
      Explanation pidfExplain = pidfExplain(collectionStats, stat);
      details.add(pidfExplain);
      pidf += pidfExplain.getValue().floatValue();
    }
    return Explanation.match((float) pidf, "pidf, sum of:", details);
  }

  @Override
  public final SimScorer scorer(float boost, CollectionStatistics collectionStats, TermStatistics... termStats) {
    Explanation ictf = termStats.length == 1 ? ictfExplain(collectionStats, termStats[0]) : ictfExplain(collectionStats, termStats);
    Explanation pidf = termStats.length == 1 ? pidfExplain(collectionStats, termStats[0]) : pidfExplain(collectionStats, termStats);
    Explanation idf = termStats.length == 1 ? idfExplain(collectionStats, termStats[0]) : idfExplain(collectionStats, termStats);
    float avgdl = avgFieldLength(collectionStats);

    float[] cache = new float[256];
    for (int i = 0; i < cache.length; i++) {
      cache[i] = k1 * ((1 - b) + b * LENGTH_TABLE[i] / avgdl);
    }
    return new BM25Scorer(boost, k1, b, d, ictf, pidf, idf, avgdl, cache);
  }
  
  /** Collection statistics for the BM25 model. */
  private static class BM25Scorer extends SimScorer {
    /** query boost */
    private final float boost;
    /** k1 value for scale factor */
    private final float k1;
    /** b value for length normalization impact */
    private final float b;
    /** d parameter */
    private final float d;
    /** BM25's ictf */
    private final Explanation ictf;
    /** BM25's pidf */
    private final Explanation pidf;
    /** BM25's idf */
    private final Explanation idf;
    /** The average document length. */
    private final float avgdl;
    /** precomputed norm[256] with k1 * ((1 - b) + b * dl / avgdl) */
    private final float[] cache;
    /** weight (idf * boost) */
    private final float weight;

    BM25Scorer(float boost, float k1, float b, float d, Explanation ictf, Explanation pidf, Explanation idf, float avgdl, float[] cache) {
      this.boost = boost;
      this.ictf = ictf;
      this.pidf = pidf;
      this.idf = idf;
      this.avgdl = avgdl;
      this.k1 = k1;
      this.b = b;
      this.d = d;
      this.cache = cache;
      this.weight = (k1 + 1) * boost * ictf.getValue().floatValue() * pidf.getValue().floatValue() * idf.getValue().floatValue();
    }

    @Override
    public float score(float freq, long encodedNorm) {
      double norm = cache[((byte) encodedNorm) & 0xFF];
      return weight * (float) (freq / (freq + norm) + d) ;
    }

    @Override
    public Explanation explain(Explanation freq, long encodedNorm) {
      List<Explanation> subs = new ArrayList<>(explainConstantFactors());
      Explanation tfExpl = explainTF(freq, encodedNorm);
      subs.add(tfExpl);
      return Explanation.match(weight * tfExpl.getValue().floatValue(),
          "score(freq="+freq.getValue()+"), product of:", subs);
    }
    
    private Explanation explainTF(Explanation freq, long norm) {
      List<Explanation> subs = new ArrayList<>();
      subs.add(freq);
      subs.add(Explanation.match(k1, "k1, term saturation parameter"));
      float doclen = LENGTH_TABLE[((byte) norm) & 0xff];
      subs.add(Explanation.match(b, "b, length normalization parameter"));
      if ((norm & 0xFF) > 39) {
        subs.add(Explanation.match(doclen, "dl, length of field (approximate)"));
      } else {
        subs.add(Explanation.match(doclen, "dl, length of field"));
      }
      subs.add(Explanation.match(avgdl, "avgdl, average length of field"));
      float normValue = k1 * ((1 - b) + b * doclen / avgdl);
      return Explanation.match(
          (float) (freq.getValue().floatValue() / (freq.getValue().floatValue() + (double) normValue)),
          "tf, computed as freq / (freq + k1 * (1 - b + b * dl / avgdl)) from:", subs);
    }

    private List<Explanation> explainConstantFactors() {
      List<Explanation> subs = new ArrayList<>();
      // scale factor
      subs.add(Explanation.match(k1 + 1, "scaling factor, k1 + 1"));
      // query boost
      if (boost != 1.0f) {
        subs.add(Explanation.match(boost, "boost"));
      }
      // ictf
      subs.add(ictf);
      // pidf
      subs.add(pidf);
      // idf
      subs.add(idf);
      return subs;
    }
  }

  @Override
  public String toString() {
    return "BM25(k1=" + k1 + ",b=" + b + ")";
  }
  
  /** 
   * Returns the <code>k1</code> parameter
   * @see #BM25CtfSimilarity(float, float, float) 
   */
  public final float getK1() {
    return k1;
  }
  
  /**
   * Returns the <code>b</code> parameter 
   * @see #BM25CtfSimilarity(float, float, float) 
   */
  public final float getB() {
    return b;
  }

  /**
   * Returns the <code>d</code> parameter 
   * @see #BM25CtfSimilarity(float, float, float) 
   */
  public final float getD() {
    return d;
  }
}
