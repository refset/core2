(ns core2.operator.select
  (:require [core2.coalesce :as coalesce]
            [core2.util :as util]
            [core2.vector.indirect :as iv])
  (:import core2.ICursor
           core2.vector.IIndirectRelation
           core2.operator.IRelationSelector
           java.util.function.Consumer
           org.apache.arrow.memory.BufferAllocator))

(set! *unchecked-math* :warn-on-boxed)

(deftype SelectCursor [^BufferAllocator allocator, ^ICursor in-cursor, ^IRelationSelector selector]
  ICursor
  (getColumnNames [_] (.getColumnNames in-cursor))

  (tryAdvance [_ c]
    (let [advanced? (boolean-array 1)]
      (while (and (.tryAdvance in-cursor
                               (reify Consumer
                                 (accept [_ in-rel]
                                   (let [^IIndirectRelation in-rel in-rel]
                                     (when-let [idxs (.select selector allocator in-rel)]
                                       (when-not (zero? (alength idxs))
                                         (.accept c (iv/select in-rel idxs))
                                         (aset advanced? 0 true)))))))
                  (not (aget advanced? 0))))
      (aget advanced? 0)))

  (close [_]
    (util/try-close in-cursor)))

(defn ->select-cursor ^core2.ICursor [^BufferAllocator allocator, ^ICursor in-cursor, ^IRelationSelector selector]
  (-> (SelectCursor. allocator in-cursor selector)
      (coalesce/->coalescing-cursor allocator)))
