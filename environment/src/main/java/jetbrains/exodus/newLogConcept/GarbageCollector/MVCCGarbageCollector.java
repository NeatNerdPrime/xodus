package jetbrains.exodus.newLogConcept.GarbageCollector;

import com.sun.source.tree.AssertTree;
import jetbrains.exodus.ExodusException;
import jetbrains.exodus.newLogConcept.MVCC.MVCCRecord;
import jetbrains.exodus.newLogConcept.Transaction.TransactionState;
import org.jctools.maps.NonBlockingHashMapLong;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

public class MVCCGarbageCollector {

    public Long findMaxMinId(ConcurrentSkipListMap<Long, TransactionGCEntry> transactionsGCMap, Long snapshotId) {
        Long maxMinId = null;
        Long prevKey = null;
        Long head;
        if (snapshotId <= transactionsGCMap.lastKey())
            head = snapshotId;
        else head = transactionsGCMap.lastKey();

        for (Map.Entry<Long, TransactionGCEntry> entry
                : transactionsGCMap.headMap(head, true).entrySet()) {
            Long currentKey = entry.getKey();
            int state = entry.getValue().stateWrapper.state;
            if (state == TransactionState.COMMITTED.get() || state == TransactionState.REVERTED.get()) {
                if (prevKey == null || currentKey == prevKey + 1 || (currentKey - 1 <= transactionsGCMap.get(prevKey).upToId)) {
                    prevKey = currentKey;
                    maxMinId = currentKey;
                } else {
                    return maxMinId;
                }
            } else {
                return maxMinId;
            }
        }
        return maxMinId;
    }

    // todo multithreading
    // TODO: take into account merged records!
    public ConcurrentSkipListSet<Long> findMissingOrActiveTransactionsIds(Long maxMinId, Long snapshotId,
                                       ConcurrentSkipListMap<Long, TransactionGCEntry> transactionsGCMap) {
        if (snapshotId < maxMinId){
            throw new ExodusException();
        }
        ConcurrentSkipListSet<Long> result = new ConcurrentSkipListSet<>();
        Long lastKey = null;
        for (long i = maxMinId + 1; i < snapshotId; i++) {
            if (isKeyMissing(i, transactionsGCMap)) {
                result.add(i);
            } else {
                if (transactionsGCMap.containsKey(i)){
                    int state = transactionsGCMap.get(i).stateWrapper.state;
                    if (state == TransactionState.IN_PROGRESS.get() || (lastKey != null && i != lastKey + 1)) {
                        result.add(i);
                    }
                }
            }
            lastKey = i;
        }
        return result;
    }

    private boolean isKeyMissing(long key, ConcurrentSkipListMap<Long, TransactionGCEntry> transactionsGCMap) {
        for (var element: transactionsGCMap.entrySet()){
            if (element.getKey() == key || element.getValue().upToId >= key && element.getKey() < key){
                return false;
            }
        }
        return true;
    }

    void removeUpToMaxMinId(long snapshotId, NonBlockingHashMapLong<MVCCRecord> mvccHashMap,
                            ConcurrentSkipListMap<Long, TransactionGCEntry> transactionsGCMap) {
        AtomicLong maxMinId = new AtomicLong(findMaxMinId(transactionsGCMap, snapshotId)); //todo define consistent type everywhere
        // todo check multithreading everywhere
        if (snapshotId < maxMinId.get()){
            throw new ExodusException();
        }
        removeTransactionsRange(transactionsGCMap.firstKey(), maxMinId.getAndDecrement(), mvccHashMap,
                transactionsGCMap, true);
    }

    void removeTransactionsRange(long transactionIdStart, long transactionIdEnd,
                                 NonBlockingHashMapLong<MVCCRecord> mvccHashMap,
                                 ConcurrentSkipListMap<Long, TransactionGCEntry> transactionsGCMap,
                                 boolean isUpToMaxMinId) {
        for (long id = transactionIdEnd; id > transactionIdStart; id--) {
            mvccHashMap.remove(id);
            transactionsGCMap.remove(id);
        }
        // if the sequence is up to maxMinId, then we delete the whole sequence, otherwise the first element is not deleted
        if (isUpToMaxMinId) {
            mvccHashMap.remove(transactionIdStart);
            transactionsGCMap.remove(transactionIdStart);
        } else {
            transactionsGCMap.get(transactionIdStart).upToId = transactionIdEnd;
        }
    }
}