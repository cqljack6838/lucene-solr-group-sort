package org.apache.lucene.search.grouping.docscores;

/**
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

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.ScoreCachingWrappingScorer;
import org.apache.lucene.search.Scorer;

/**
 * Implementations of group score comparators that work on aggregating document
 * scores within groups as the basis of sorting.
 * 
 * @lucene.experimental
 */
public abstract class DocScoresGroupComparator extends FieldComparator<Float> {
  protected float[] groupScores;
  protected float[] topScores;
  protected int[] topDocs;
  protected float bottom;
  protected float lastCopy;
  protected Scorer scorer;
  protected static int numHitsPadding;
  
  public static enum Type {
    SUM
  };
  
  public static DocScoresGroupComparator create(Type type, int numHits) {
    switch (type) {
      case SUM:
        return new DocScoresSumGroupComparator(numHits);
      default:
        throw new IllegalStateException("Illegal compartor type: " + type);
    }
  }
  
  DocScoresGroupComparator(int numHits) {
    groupScores = new float[numHits];
    topScores = new float[numHits];
    topDocs = new int[numHits];
    Arrays.fill(topScores, 0);
    numHitsPadding=numHits;
  }
  
  static class DocScoresSumGroupComparator extends DocScoresGroupComparator {

    DocScoresSumGroupComparator(int numHits) {
      super(numHits);
    }
    
    @Override
    public void copy(int slot, int doc) throws IOException {
      float score = scorer.score();
      groupScores=checkFloatArraySize(groupScores,slot);
      groupScores[slot] += score;
      topScores=checkFloatArraySize(topScores,slot);
      if(score>topScores[slot])
      {
        topScores[slot]=score;
        topDocs=checkIntArraySize(topDocs,slot);
        topDocs[slot]=doc;
      }
      this.lastCopy = score;
    }
  }
  
  private static float[] checkFloatArraySize(float[] array, int slot) {
    if(slot<array.length)
      return array;
    else {
      array = Arrays.copyOf(array, array.length+numHitsPadding);
      return checkFloatArraySize(array, slot);
    }
  }
  
  private static int[] checkIntArraySize(int[] array, int slot) {
    if(slot<array.length)
      return array;
    else {
      array = Arrays.copyOf(array, array.length+numHitsPadding);
      return checkIntArraySize(array, slot);
    }
  }
  
  @Override
  public int compare(int slot1, int slot2) {
    final float score1 = groupScores[slot1];
    final float score2 = groupScores[slot2];
    return score1 > score2 ? -1 : (score1 < score2 ? 1 : 0);
  }
  
  @Override
  public int compareBottom(int doc) throws IOException {
    float score = scorer.score();
    return bottom > score ? -1 : (bottom < score ? 1 : 0);
  }
  
  @Override
  public abstract void copy(int slot, int doc) throws IOException;
  
  @Override
  public FieldComparator setNextReader(AtomicReaderContext context) {
    return this;
  }
  
  @Override
  public void setBottom(final int bottom) {
    this.bottom = groupScores[bottom];
  }
  
  @Override
  public void setScorer(Scorer scorer) {
    // wrap with a ScoreCachingWrappingScorer so that successive calls to
    // score() will not incur score computation over and
    // over again.
    if (!(scorer instanceof ScoreCachingWrappingScorer)) {
      this.scorer = new ScoreCachingWrappingScorer(scorer);
    } else {
      this.scorer = scorer;
    }
  }
  
  @Override
  public Float value(int slot) {
    return Float.valueOf(groupScores[slot]);
  }
  
  // Override because we sort reverse of natural Float order:
  @Override
  public int compareValues(Float first, Float second) {
    // Reversed intentionally because relevance by default
    // sorts descending:
    return second.compareTo(first);
  }

  public float getLastCopy() {
    return lastCopy;
  }
  
  public float getTopScore(int slot) {
    return topScores[slot];
  }

  public int getTopDoc(int slot) {
    return topDocs[slot];
  }
}
