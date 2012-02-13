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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.TreeSet;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.FieldCache;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.grouping.CollectedSearchGroup;
import org.apache.lucene.search.grouping.SearchGroup;
import org.apache.lucene.search.grouping.term.TermFirstPassGroupingCollector;
import org.apache.lucene.util.BytesRef;

/**
 * Implementation of a {@link Collector} similar to
 * {@link TermFirstPassGroupingCollector} that sorts groups based on aggregate
 * document score functions (e.g. sum(score), avg(score), etc).
 * 
 * @lucene.experimental
 */
public abstract class DocScoresFirstPassGroupingCollector extends Collector {
  
  protected final Sort groupSort;
  protected FieldComparator comparator;
  protected final int reversed;
//  protected final int topNGroups;
  protected final HashMap<BytesRef,CollectedSearchGroup<BytesRef>> groupMap;
  private final int compIDXEnd;
  private final BytesRef scratchBytesRef = new BytesRef();
  private FieldCache.DocTermsIndex index;
  // Set once we reach topNGroups unique groups:
  /** @lucene.internal */
  protected TreeSet<CollectedSearchGroup<BytesRef>> orderedGroups;
  protected int docBase;
  private String groupField;
  
  public static DocScoresFirstPassGroupingCollector create(DocScoresGroupComparator.Type type, String groupField,
      int topNGroups) throws IOException {
    switch (type) {
      case SUM:
        return new DocScoresSum(groupField, topNGroups);
      default:
        throw new IllegalStateException("Illegal compartor type: " + type);
    }
  }
  
  protected DocScoresFirstPassGroupingCollector(String groupField, Sort groupSort, int topNGroups) throws IOException {
    if (topNGroups < 1) {
      throw new IllegalArgumentException("topNGroups must be >= 1 (got " + topNGroups + ")");
    }
    
    // TODO: allow null groupSort to mean "by relevance",
    // and specialize it?
    this.groupSort = groupSort;
    
    //this.topNGroups = topNGroups;
    
    comparator = groupSort.getSort()[0].getComparator(topNGroups + 1, 0);
    compIDXEnd = 0;
    reversed = groupSort.getSort()[0].getReverse() ? -1 : 1;
    
    groupMap = new HashMap<BytesRef,CollectedSearchGroup<BytesRef>>(topNGroups);
    this.groupField = groupField;
  }
  
  static class DocScoresSum extends DocScoresFirstPassGroupingCollector {
    private static final SortField SCORE_SUM_SORT_FIELD = new SortField("foo", new DocScoresGroupComparatorSource(
        DocScoresGroupComparator.Type.SUM));
    private static final Sort SCORE_SUM_SORT = new Sort(SCORE_SUM_SORT_FIELD);
    
    public DocScoresSum(String groupField, int topNGroups) throws IOException {
      super(groupField, SCORE_SUM_SORT, topNGroups);
    }
  }
  
  @Override
  public void setScorer(Scorer scorer) throws IOException {
    comparator.setScorer(scorer);
  }
  
  @Override
  public void collect(int doc) throws IOException {
    BytesRef groupValue = copyDocGroupValue(getDocGroupValue(doc), null);
    CollectedSearchGroup<BytesRef> group = groupMap.get(groupValue);
    if (group == null) {
      // Add a new CollectedSearchGroup:
      group = new CollectedSearchGroup<BytesRef>();
      group.groupValue = copyDocGroupValue(groupValue, null);
      group.comparatorSlot = groupMap.size();
      groupMap.put(group.groupValue, group);
    }
    comparator.copy(group.comparatorSlot, doc);
//    group.groupScore = (Float) comparator.value(group.comparatorSlot);
    group.topDoc = ((DocScoresGroupComparator) comparator).getTopDoc(group.comparatorSlot);
  }
  
  @Override
  public boolean acceptsDocsOutOfOrder() {
    return false;
  }
  
  @Override
  public void setNextReader(AtomicReaderContext readerContext) throws IOException {
    docBase = readerContext.docBase;
    comparator = comparator.setNextReader(readerContext);
    index = FieldCache.DEFAULT.getTermsIndex(readerContext.reader(), groupField);
  }
  
  /**
   * Returns the group value for the specified doc.
   * 
   * @param doc
   *          The specified doc
   * @return the group value for the specified doc
   */
  protected BytesRef getDocGroupValue(int doc) {
    final int ord = index.getOrd(doc);
    return ord == 0 ? null : index.lookup(ord, scratchBytesRef);
  }
  
  /**
   * Returns a copy of the specified group value by creating a new instance and
   * copying the value from the specified groupValue in the new instance. Or
   * optionally the reuse argument can be used to copy the group value in.
   * 
   * @param groupValue
   *          The group value to copy
   * @param reuse
   *          Optionally a reuse instance to prevent a new instance creation
   * @return a copy of the specified group value
   */
  protected BytesRef copyDocGroupValue(BytesRef groupValue, BytesRef reuse) {
    if (groupValue == null) {
      return null;
    } else if (reuse != null) {
      reuse.copyBytes(groupValue);
      return reuse;
    } else {
      return BytesRef.deepCopyOf(groupValue);
    }
  }
  
  /**
   * Returns top groups, starting from offset. This may return null, if no
   * groups were collected, or if the number of unique groups collected is <=
   * offset.
   * 
   * @param groupOffset
   *          The offset in the collected groups
   * @param fillFields
   *          Whether to fill to {@link SearchGroup#sortValues}
   * @return top groups, starting from offset
   */
  public Collection<SearchGroup<BytesRef>> getTopGroups(int groupOffset, boolean fillFields) {
    if (groupOffset < 0) {
      throw new IllegalArgumentException("groupOffset must be >= 0 (got " + groupOffset + ")");
    }
    
    if (groupMap.size() <= groupOffset) {
      return null;
    }
    
    if (orderedGroups == null) {
      buildSortedSet();
    }
    
    final Collection<SearchGroup<BytesRef>> result = new ArrayList<SearchGroup<BytesRef>>();
    int upto = 0;
    for (CollectedSearchGroup<BytesRef> group : orderedGroups) {
      if (upto++ < groupOffset) {
        continue;
      }
      // System.out.println("  group=" + (group.groupValue == null ? "null" :
      // group.groupValue.utf8ToString()));
      SearchGroup<BytesRef> searchGroup = new SearchGroup<BytesRef>();
      searchGroup.groupValue = group.groupValue;
      if (fillFields) {
        searchGroup.sortValues = new Object[1];
        searchGroup.sortValues[0] = comparator.value(group.comparatorSlot);
      }
      result.add(searchGroup);
      
      // Ensure we cap at topNGroups
      // if (result.size() == topNGroups) break;
    }
    // System.out.println("  return " + result.size() + " groups");
    return result;
  }
  
  protected void buildSortedSet() {
    final Comparator<CollectedSearchGroup> groupComparator = new Comparator<CollectedSearchGroup>() {
      public int compare(CollectedSearchGroup o1, CollectedSearchGroup o2) {
        for (int compIDX = 0;; compIDX++) {
          final int c = reversed * comparator.compare(o1.comparatorSlot, o2.comparatorSlot);
          if (c != 0) {
            return c;
          } else if (compIDX == compIDXEnd) {
            return o1.topDoc - o2.topDoc;
          }
        }
      }
    };
    
    orderedGroups = new TreeSet<CollectedSearchGroup<BytesRef>>(groupComparator);
    orderedGroups.addAll(groupMap.values());
    assert orderedGroups.size() > 0;
    
    comparator.setBottom(orderedGroups.last().comparatorSlot);
  }
  
}
