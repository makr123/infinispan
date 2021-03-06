package org.infinispan.stream.impl.termop.primitive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.BaseStream;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

import org.infinispan.stream.impl.KeyTrackingTerminalOperation;
import org.infinispan.stream.impl.intops.IntermediateOperation;
import org.infinispan.stream.impl.termop.BaseTerminalOperation;

/**
 * Terminal rehash aware operation that handles for each where no flat map operations are defined on a
 * {@link DoubleStream}. Note this means it is an implied map intermediate operation.
 * @param <Original> original stream type
 * @param <K> key type of the supplied stream
 */
public abstract class AbstractForEachDoubleOperation<Original, K> extends BaseTerminalOperation<Original>
      implements KeyTrackingTerminalOperation<Original, K, Double> {
   private final int batchSize;
   private final Function<? super Original, ? extends K> toKeyFunction;

   public AbstractForEachDoubleOperation(Iterable<IntermediateOperation> intermediateOperations,
         Supplier<Stream<Original>> supplier, Function<? super Original, ? extends K> toKeyFunction, int batchSize) {
      super(intermediateOperations, supplier);
      this.toKeyFunction = toKeyFunction;
      this.batchSize = batchSize;
   }

   @Override
   public boolean lostSegment(boolean stopIfLost) {
      // TODO: stop this early
      return true;
   }

   @Override
   public List<Double> performOperation(IntermediateCollector<Collection<Double>> response) {
      /**
       * This is for rehash only! {@link org.infinispan.stream.impl.termop.SingleRunOperation} should always be used for
       * non rehash
       */
      throw new UnsupportedOperationException();
   }

   protected abstract void handleArray(double[] array, int size);

   @Override
   public Collection<K> performForEachOperation(IntermediateCollector<Collection<K>> response) {
      // We only support sequential streams for iterator rehash aware
      Stream<Original> originalStream = supplier.get().sequential();

      List<K> collectedValues = new ArrayList<>(batchSize);

      double[] list = new double[batchSize];
      AtomicInteger offset = new AtomicInteger();
      K[] currentKey = (K[]) new Object[1];
      originalStream = originalStream.peek(e -> {
         if (offset.get() > 0) {
            collectedValues.add(currentKey[0]);
            if (collectedValues.size() >= batchSize) {
               handleArray(list, offset.get());
               response.sendDataResonse(collectedValues);
               collectedValues.clear();
               offset.set(0);
            }
         }
         currentKey[0] = toKeyFunction.apply(e);
      });

      BaseStream<?, ?> stream = originalStream;
      for (IntermediateOperation intermediateOperation : intermediateOperations) {
         stream = intermediateOperation.perform(stream);
      }

      DoubleStream convertedStream = ((DoubleStream)stream);
      // We rely on the fact that iterator processes 1 entry at a time when sequential
      convertedStream.forEach(d -> list[offset.getAndIncrement()] = d);
      if (offset.get() > 0) {
         handleArray(list, offset.get());
         collectedValues.add(currentKey[0]);
      }
      return collectedValues;
   }

   public Function<? super Original, ? extends K> getToKeyFunction() {
      return toKeyFunction;
   }

   public int getBatchSize() {
      return batchSize;
   }
}
