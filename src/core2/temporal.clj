(ns core2.temporal
  (:require [core2.metadata :as meta]
            [core2.types :as t]
            [core2.util :as util])
  (:import core2.buffer_pool.BufferPool
           core2.metadata.IMetadataManager
           org.apache.arrow.memory.util.ArrowBufPointer
           [org.apache.arrow.memory ArrowBuf BufferAllocator]
           [org.apache.arrow.vector.types.pojo Field Schema]
           [org.apache.arrow.vector BigIntVector TimeStampMilliVector VectorSchemaRoot]
           org.apache.arrow.vector.complex.DenseUnionVector
           org.roaringbitmap.longlong.Roaring64Bitmap
           java.nio.ByteBuffer
           [java.util List Map HashMap SortedMap SortedSet TreeMap]
           [java.util.function Consumer Function LongConsumer]
           [java.util.concurrent CompletableFuture ConcurrentHashMap]
           java.io.Closeable))

(set! *unchecked-math* :warn-on-boxed)

(definterface ITemporalManager
  (^void updateTemporalCoordinates [^java.util.SortedMap row-id->temporal-coordinates])
  (^org.roaringbitmap.longlong.Roaring64Bitmap removeTombstonesFrom [^org.roaringbitmap.longlong.Roaring64Bitmap row-id-bitmap])
  (^org.apache.arrow.vector.VectorSchemaRoot createTemporalRoot [^java.util.List columns
                                                                 ^org.roaringbitmap.longlong.Roaring64Bitmap row-id-bitmap]))

(def ^org.apache.arrow.vector.types.pojo.Field tx-time-end-field
  (t/->primitive-dense-union-field "_tx-time-end" #{:timestampmilli}))

(def ^:private timestampmilli-type-id
  (-> (t/primitive-type->arrow-type :timestampmilli)
      (t/arrow-type->type-id)))

(def ^org.apache.arrow.vector.types.pojo.Schema tx-time-end-schema (Schema. [t/row-id-field tx-time-end-field]))

(deftype TemporalManager [^BufferAllocator allocator
                          ^BufferPool buffer-pool
                          ^IMetadataManager metadata-manager
                          ^Map row-id->tx-time-end
                          ^Map id->row-id
                          ^Roaring64Bitmap tombstone-row-ids]
  ITemporalManager
  (updateTemporalCoordinates [_ row-id->temporal-coordinates]
    ;; TODO: should this be properly atomic, or do we take a lock
    ;; between this a root creation?
    (doseq [^Map coordinates (vals row-id->temporal-coordinates)
            :let [id (.get coordinates "_id")
                  row-id (.get coordinates "_row-id")
                  tx-time (.get coordinates "_tx-time")
                  id (if (bytes? id)
                       (ByteBuffer/wrap id)
                       id)]]
      (when-let [prev-row-id (.get id->row-id id)]
        (.put row-id->tx-time-end prev-row-id tx-time))
      (.put id->row-id id row-id)

      (when (.get coordinates "_tombstone")
        (.addLong tombstone-row-ids row-id))))

  (removeTombstonesFrom [_ row-id-bitmap]
    (doto row-id-bitmap
      (.andNot tombstone-row-ids)))

  (createTemporalRoot [_ columns row-id-bitmap]
    (assert (= ["_tx-time-end"] columns))
    (let [out-root (VectorSchemaRoot/create allocator tx-time-end-schema)
          ^BigIntVector row-id-vec (.getVector out-root 0)
          ^DenseUnionVector tx-time-end-duv-vec (.getVector out-root 1)
          ^TimeStampMilliVector tx-time-end-vec (.getVectorByType tx-time-end-duv-vec timestampmilli-type-id)
          row-id-stream (.stream row-id-bitmap) ;; TODO: is this stream really thread safe?
          value-count (.count row-id-stream)]
      (util/set-value-count row-id-vec value-count)
      (util/set-value-count tx-time-end-duv-vec value-count)
      (util/set-value-count tx-time-end-vec value-count)
      (.forEach row-id-stream
                (reify LongConsumer
                  (accept [_ row-id]
                    (let [row-count (.getRowCount out-root)
                          offset (util/write-type-id tx-time-end-duv-vec row-count timestampmilli-type-id)
                          ^long tx-time-end (.getOrDefault row-id->tx-time-end row-id Long/MAX_VALUE)]
                      (.set row-id-vec row-count row-id)
                      (.set tx-time-end-vec row-count tx-time-end)
                      (util/set-vector-schema-root-row-count out-root row-count)))))
      out-root))

  Closeable
  (close [_]
    (.clear row-id->tx-time-end)
    (.clear id->row-id)
    (.clear tombstone-row-ids)))

(defn- populate-known-chunks ^core2.temporal.ITemporalManager [^TemporalManager temporal-manager]
  (let [^BufferPool buffer-pool (.buffer-pool temporal-manager)
        ^IMetadataManager metadata-manager (.metadata-manager temporal-manager)
        known-chunks (.knownChunks metadata-manager)
        futs (reduce (fn [acc chunk-idx]
                       (into acc (for [col-name ["_id" "_tx-time" "_valid-time" "_valid-time-end" "_tombstone"]]
                                   (-> (.getBuffer buffer-pool (meta/->chunk-obj-key chunk-idx col-name))
                                       (util/then-apply util/try-close)))))
                     []
                     known-chunks)]
    @(CompletableFuture/allOf (into-array CompletableFuture futs))
    (doseq [chunk-idx known-chunks
            :let [row-id->temporal-coordinates (TreeMap.)]]
      (with-open [^ArrowBuf id-buffer @(.getBuffer buffer-pool (meta/->chunk-obj-key chunk-idx "_id"))
                  id-chunks (util/->chunks id-buffer (.allocator temporal-manager))]
        (.forEachRemaining id-chunks
                           (reify Consumer
                             (accept [_ id-root]
                               (let [^VectorSchemaRoot id-root id-root
                                     ^BigIntVector row-id-vec (.getVector id-root 0)
                                     id-vec (.getVector id-root 1)]
                                 (dotimes [n (.getRowCount id-root)]
                                   (let [row-id (.get row-id-vec n)]
                                     (.put row-id->temporal-coordinates
                                           row-id
                                           (doto (HashMap.)
                                             (.put "_row-id" row-id)
                                             (.put "_id" (.getObject id-vec n)))))))))))


      (doseq [col-name ["_tx-time" "_valid-time" "_valid-time-end"]]
        (when-let [temporal-buffer @(.getBuffer buffer-pool (meta/->chunk-obj-key chunk-idx col-name))]
          (with-open [^ArrowBuf temporal-buffer temporal-buffer
                      temporal-chunks (util/->chunks temporal-buffer (.allocator temporal-manager))]
            (.forEachRemaining temporal-chunks
                               (reify Consumer
                                 (accept [_ temporal-root]
                                   (let [^VectorSchemaRoot temporal-root temporal-root
                                         ^BigIntVector row-id-vec (.getVector temporal-root 0)
                                         ^DenseUnionVector temporal-duv-vec (.getVector temporal-root 1)
                                         ^TimeStampMilliVector temporal-vec (.getVectorByType temporal-duv-vec timestampmilli-type-id)]
                                     (dotimes [n (.getRowCount temporal-root)]
                                       (-> ^Map (.get row-id->temporal-coordinates (.get row-id-vec n))
                                           (.put col-name (.get temporal-vec n)))))))))))

      (when-let [tombstone-buffer @(.getBuffer buffer-pool (meta/->chunk-obj-key chunk-idx "_tombstone"))]
        (with-open [^ArrowBuf tombstone-buffer tombstone-buffer
                    tombstone-chunks (util/->chunks tombstone-buffer (.allocator temporal-manager))]
          (.forEachRemaining tombstone-chunks
                             (reify Consumer
                               (accept [_ tombstone-root]
                                 (let [^VectorSchemaRoot tombstone-root tombstone-root
                                       ^BigIntVector row-id-vec (.getVector tombstone-root 0)
                                       ^DenseUnionVector tombstone-duv-vec (.getVector tombstone-root 1)]
                                   (dotimes [n (.getRowCount tombstone-root)]
                                     (when (.getObject tombstone-duv-vec n)
                                       (-> ^Map (.get row-id->temporal-coordinates (.get row-id-vec n))
                                           (.put "_tombstone" true))))))))))

      (.updateTemporalCoordinates temporal-manager row-id->temporal-coordinates))

    temporal-manager))

(defn ->temporal-manager ^core2.temporal.ITemporalManager [^BufferAllocator allocator
                                                           ^BufferPool buffer-pool
                                                           ^IMetadataManager metadata-manager]
  (-> (TemporalManager. allocator buffer-pool metadata-manager (ConcurrentHashMap.) (HashMap.) (Roaring64Bitmap.))
      (populate-known-chunks)))
