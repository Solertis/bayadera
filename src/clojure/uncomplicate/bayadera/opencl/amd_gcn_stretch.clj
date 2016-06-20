(ns ^{:author "Dragan Djuric"}
    uncomplicate.bayadera.opencl.amd-gcn-stretch
  (:require [clojure.java.io :as io]
            [uncomplicate.commons.core
             :refer [Releaseable release wrap-int
                     with-release let-release double-fn]]
            [uncomplicate.fluokitten.core :refer [op fmap fmap!]]
            [uncomplicate.clojurecl
             [core :refer :all]
             [toolbox :refer [count-work-groups enq-reduce enq-read-long]]]
            [uncomplicate.neanderthal
             [protocols :refer [data-accessor factory]]
             [math :refer [sqrt ceil]]
             [core :refer [dim create-raw create-ge-matrix transfer
                           create scal! copy matrix? ncols]]
             [block :refer [buffer]]]
            [uncomplicate.bayadera.protocols :refer :all]
            [uncomplicate.bayadera.opencl
             [models :refer [source]]
             [utils :refer [with-philox get-tmp-dir-name]]])
  (:import [uncomplicate.neanderthal.protocols DataAccessor]))

(defn ^:private inc! [^ints a]
  (doto a (aset 0 (inc (aget a 0)))))

(deftype GCNStretch [ctx cqueue neanderthal-factory ^DataAccessor claccessor
                     ^long walker-count wsize ^long DIM ^long WGS
                     ^ints step-counter ^longs iteration-counter diagnostics
                     cl-params cl-xs cl-s0 cl-s1
                     cl-logpdf-xs cl-logpdf-s0 cl-logpdf-s1
                     cl-accept cl-accept-acc cl-acc
                     stretch-move-odd-kernel stretch-move-even-kernel
                     stretch-move-odd-bare-kernel stretch-move-even-bare-kernel
                     init-walkers-kernel logpdf-kernel
                     sum-accept-reduction-kernel sum-accept-kernel
                     sum-means-kernel
                     sum-reduction-kernel sum-reduce-kernel
                     subtract-mean-kernel
                     autocovariance-kernel
                     min-max-reduction-kernel
                     min-max-kernel
                     histogram-kernel
                     uint-to-real-kernel
                     local-sort-kernel
                     mean-kernel
                     variance-kernel]
  Releaseable
  (release [_]
    (and (release cl-xs)
         (release cl-s0)
         (release cl-s1)
         (release cl-logpdf-xs)
         (release cl-logpdf-s0)
         (release cl-logpdf-s1)
         (release cl-accept)
         (release cl-accept-acc)
         (release cl-acc)
         (release stretch-move-odd-kernel)
         (release stretch-move-even-kernel)
         (release stretch-move-odd-bare-kernel)
         (release stretch-move-even-bare-kernel)
         (release init-walkers-kernel)
         (release logpdf-kernel)
         (release sum-accept-reduction-kernel)
         (release sum-accept-kernel)
         (release sum-means-kernel)
         (release sum-reduction-kernel)
         (release sum-reduce-kernel)
         (release subtract-mean-kernel)
         (release autocovariance-kernel)
         (release min-max-reduction-kernel)
         (release min-max-kernel)
         (release histogram-kernel)
         (release uint-to-real-kernel)
         (release local-sort-kernel)
         (release mean-kernel)
         (release variance-kernel)))
  MCMCStretch
  (move! [this]
    (set-arg! stretch-move-odd-kernel 8 step-counter)
    (set-arg! stretch-move-even-kernel 8 step-counter)
    (enq-nd! cqueue stretch-move-odd-kernel wsize)
    (enq-nd! cqueue stretch-move-even-kernel wsize)
    (inc! step-counter)
    cl-xs)
  (move-bare! [this]
    (set-arg! stretch-move-odd-bare-kernel 6 step-counter)
    (set-arg! stretch-move-even-bare-kernel 6 step-counter)
    (enq-nd! cqueue stretch-move-odd-bare-kernel wsize)
    (enq-nd! cqueue stretch-move-even-bare-kernel wsize)
    (inc! step-counter)
    cl-xs)
  (acc-rate [_]
    (if (pos? (aget step-counter 0))
      (do
        (enq-reduce cqueue sum-accept-kernel sum-accept-reduction-kernel
                    (count-work-groups WGS (/ walker-count 2)) WGS)
        (/ (double (enq-read-long cqueue cl-accept-acc))
           (* walker-count (aget step-counter 0))))
      Double/NaN))
  (acor [_ sample-matrix]
    (let [n (ncols sample-matrix)
          min-fac 16
          MINLAG 4
          WINMULT 16
          TAUMAX 16
          lag (max MINLAG (min (quot n min-fac) WGS))
          i-max (- n lag)
          wgsm (min DIM WGS)
          wgsn (long (/ WGS wgsm))
          wg-count (count-work-groups wgsn n)]
      (if (<= (* lag min-fac) n)
        (let-release [d (create-raw (factory claccessor) DIM)]
          (with-release [c0 (create-raw (factory claccessor) DIM)
                         cl-acc (.createDataSource claccessor (* DIM wg-count))
                         mean-vec (create-raw neanderthal-factory DIM)
                         d-acc (.createDataSource claccessor (* DIM wg-count))]
            (set-arg! sum-reduction-kernel 0 cl-acc)
            (set-args! sum-reduce-kernel 0 cl-acc (buffer sample-matrix))
            (enq-reduce cqueue sum-reduce-kernel sum-reduction-kernel
                        DIM n wgsm wgsn)
            (enq-copy! cqueue cl-acc (buffer mean-vec))
            (scal! (/ 1.0 n) mean-vec)
            (set-args! subtract-mean-kernel 0
                       (buffer sample-matrix) (buffer mean-vec))
            (enq-nd! cqueue subtract-mean-kernel (work-size-2d DIM n))
            (enq-fill! cqueue cl-acc (int-array 1))
            (enq-fill! cqueue d-acc (int-array 1))
            (set-args! autocovariance-kernel 0 (wrap-int lag) cl-acc
                       d-acc (buffer sample-matrix) (wrap-int i-max))
            (enq-nd! cqueue autocovariance-kernel (work-size-1d n))
            (set-arg! sum-reduction-kernel 0 cl-acc)
            (set-args! sum-reduce-kernel 0 cl-acc cl-acc)
            (enq-reduce cqueue sum-reduce-kernel sum-reduction-kernel
                        DIM wg-count wgsm wgsn)
            (enq-read! cqueue cl-acc (buffer c0))
            (set-arg! sum-reduce-kernel 1 d-acc)
            (enq-reduce cqueue sum-reduce-kernel sum-reduction-kernel
                        DIM wg-count wgsm wgsn)
            (enq-read! cqueue cl-acc (buffer d))
            (->Autocorrelation (fmap (double-fn /) d c0) (transfer mean-vec)
                               (fmap! sqrt (scal! (/ 1.0 (* i-max n)) d))
                               n lag)))
        (throw (IllegalArgumentException.
                (format (str "The autocorrelation time is too long relative to the variance."
                             "Number of steps (%d) must not be less than %d.")
                        n (* lag min-fac)))))))
  (diagnose [this]
    (or @diagnostics (->Diagnostics nil (acc-rate this) walker-count @iteration-counter)))
  RandomSampler
  (init! [this seed]
    (let [seed (wrap-int seed)]
      (set-arg! stretch-move-odd-kernel 0 (inc! seed))
      (set-arg! stretch-move-even-kernel 0 (inc! seed))
      (set-arg! stretch-move-odd-bare-kernel 0 (inc! seed))
      (set-arg! stretch-move-even-bare-kernel 0 (inc! seed))
      (enq-fill! cqueue cl-accept (int-array 1))
      (aset step-counter 0 0)
      this))
  (sample [this]
    (sample this walker-count))
  (sample [this n]
    (if (<= (long n) walker-count)
      (let-release [res (create-raw neanderthal-factory DIM n)]
        (enq-copy! cqueue cl-xs (buffer res))
        res)
      (throw (IllegalArgumentException.
              (format "For number of samples greater than %d, use sample! method."
                      walker-count)) )))
  (sample! [this]
    (sample! this walker-count))
  (sample! [this n]
    (let [available (* DIM (.entryWidth claccessor) walker-count)]
      (let-release [res (create-raw neanderthal-factory DIM n)]
        (loop [ofst 0 requested (* DIM (.entryWidth claccessor) (long n))]
          (move-bare! this)
          (if (<= requested available)
            (enq-copy! cqueue cl-xs (buffer res) 0 ofst requested nil nil)
            (do
              (enq-copy! cqueue cl-xs (buffer res) 0 ofst available nil nil)
              (recur (+ ofst available) (- requested available)))))
        res)))
  MCMC
  (set-position! [this position]
    (if (matrix? position)
      (let [cl-position (buffer position)]
        (if (<= (long (size cl-xs)) (long (size cl-position)))
          (enq-copy! cqueue cl-position cl-xs)
          (throw (IllegalArgumentException. "Position buffer too short."))))
      (let [seed (wrap-int position)]
        (set-arg! init-walkers-kernel 0 seed)
        (enq-nd! cqueue init-walkers-kernel
                 (work-size-1d (* DIM (long (/ walker-count 4)))))))
    (enq-nd! cqueue logpdf-kernel (work-size-1d walker-count))
    this)
  (burn-in! [this n a]
    (aset step-counter 0 0)
    (set-arg! stretch-move-odd-bare-kernel 5 a)
    (set-arg! stretch-move-even-bare-kernel 5 a)
    (dotimes [i (dec (long n))]
      (move-bare! this))
    (let [means-count (long (count-work-groups WGS (/ walker-count 2)))]
      (with-release [cl-means-acc
                     (.createDataSource claccessor (* DIM  means-count))]
        (aset step-counter 0 0)
        (enq-fill! cqueue cl-accept (int-array 1))
        (set-args! stretch-move-odd-kernel 6 cl-means-acc a)
        (set-args! stretch-move-even-kernel 6 cl-means-acc a)
        (move! this)
        (acc-rate this))))
  (run-sampler! [this n a]
    (let [n (long n)
          means-count (long (count-work-groups WGS (/ walker-count 2)))
          local-m (min means-count WGS)
          local-n (long (/ WGS local-m))
          acc-count (long (count-work-groups local-m means-count))
          wgsn (min acc-count WGS)
          wgsm (long (/ WGS wgsn))]
      (with-release [cl-means-acc (.createDataSource claccessor (* DIM  means-count n))
                     cl-acc (.createDataSource claccessor (* DIM acc-count n))
                     means (create-ge-matrix
                            neanderthal-factory DIM n
                            (cl-sub-buffer cl-acc 0 (* DIM (.entryWidth claccessor) n)))]
        (aset step-counter 0 0)
        (enq-fill! cqueue cl-means-acc (int-array 1))
        (set-args! stretch-move-odd-kernel 6 cl-means-acc a)
        (set-args! stretch-move-even-kernel 6 cl-means-acc a)
        (dotimes [i n]
          (move! this))
        (set-arg! sum-reduction-kernel 0 cl-acc)
        (set-args! sum-means-kernel 0 cl-acc cl-means-acc)
        (enq-reduce cqueue sum-means-kernel sum-reduction-kernel
                    means-count (* DIM n) local-m local-n wgsm wgsn)
        (scal! (/ 0.5 (* WGS means-count)) means)
        (aset iteration-counter 0 (+ (aget iteration-counter 0) n))
        (vreset! diagnostics
                 (->Diagnostics (acor this means)
                                (acc-rate this)
                                walker-count
                                (aget iteration-counter 0))))))
  EstimateEngine
  (histogram [this]
    (histogram! this 1))
  (histogram! [this cycles]
    (let [cycles (long cycles)
          n (* cycles walker-count)
          wgsm (min DIM (long (sqrt WGS)))
          wgsn (long (/ WGS wgsm))
          histogram-worksize (work-size-2d DIM walker-count 1 WGS)
          acc-size (* 2 (max 1 (* DIM (count-work-groups WGS walker-count))))]
      (with-release [cl-min-max (.createDataSource claccessor acc-size)
                     uint-res (cl-buffer ctx (* Integer/BYTES WGS DIM) :read-write)
                     result (create-raw neanderthal-factory WGS DIM)
                     limits (create-raw neanderthal-factory 2 DIM)
                     bin-ranks (create-raw neanderthal-factory WGS DIM)]
        (set-arg! min-max-reduction-kernel 0 cl-min-max)
        (set-args! min-max-kernel cl-min-max cl-xs)
        (enq-reduce cqueue
                    min-max-kernel min-max-reduction-kernel
                    DIM walker-count wgsm wgsn)
        (enq-copy! cqueue cl-min-max (buffer limits))
        (enq-fill! cqueue uint-res (int-array 1))
        (set-args! histogram-kernel
                   (buffer limits) cl-xs
                   (wrap-int (* DIM walker-count)) uint-res)
        (enq-nd! cqueue histogram-kernel histogram-worksize)
        (dotimes [i (dec cycles)]
          (move-bare! this)
          (enq-nd! cqueue histogram-kernel histogram-worksize))
        (set-args! uint-to-real-kernel
                   (.wrapPrim claccessor (/ WGS n)) (buffer limits)
                   uint-res (buffer result))
        (enq-nd! cqueue uint-to-real-kernel (work-size-2d WGS DIM))
        (set-args! local-sort-kernel (buffer result) (buffer bin-ranks))
        (enq-nd! cqueue local-sort-kernel (work-size-1d (* DIM WGS)))
        (aset iteration-counter 0 (+ (aget iteration-counter 0) (inc cycles)))
        (->Histogram (transfer limits) (transfer result) (transfer bin-ranks)))))
  Location
  (mean [_]
    (let-release [res-vec (create-raw (factory claccessor) DIM)]
      (set-arg! sum-reduction-kernel 0 cl-acc)
      (enq-reduce cqueue mean-kernel sum-reduction-kernel DIM walker-count 1 WGS)
      (enq-read! cqueue cl-acc (buffer res-vec))
      (scal! (/ 1.0 walker-count) res-vec)))
  Spread
  (variance [_]
    (let-release [res-vec (create-raw neanderthal-factory DIM)]
      (set-arg! sum-reduction-kernel 0 cl-acc)
      (enq-reduce cqueue mean-kernel sum-reduction-kernel DIM walker-count 1 WGS)
      (enq-copy! cqueue cl-acc (buffer res-vec))
      (scal! (/ 1.0 walker-count) res-vec)
      (set-arg! variance-kernel 2 (buffer res-vec))
      (enq-reduce cqueue variance-kernel sum-reduction-kernel DIM walker-count 1 WGS)
      (enq-copy! cqueue cl-acc (buffer res-vec))
      (scal! (/ 1.0 walker-count) (transfer res-vec))))
  (sd [this]
    (fmap! sqrt (variance this))))

(deftype GCNStretchFactory [ctx queue neanderthal-factory prog ^long DIM ^long WGS]
  Releaseable
  (release [_]
    (release prog))
  MCMCFactory
  (mcmc-sampler [_ walker-count params host-limits]
    (let [walker-count (long walker-count)]
      (if (and (<= (* 2 WGS) walker-count) (zero? (rem walker-count (* 2 WGS))))
        (let [acc-count (* DIM (count-work-groups WGS walker-count))
              accept-count (count-work-groups WGS (/ walker-count 2))
              accept-acc-count (count-work-groups WGS accept-count)
              claccessor (data-accessor neanderthal-factory)
              sub-bytesize (* DIM (long (/ walker-count 2)) (.entryWidth claccessor))
              step-counter (int-array 1)
              cl-params (buffer params)]
          (let-release [cl-xs (.createDataSource claccessor (* DIM walker-count))
                        cl-s0 (cl-sub-buffer cl-xs 0 sub-bytesize :read-write)
                        cl-s1 (cl-sub-buffer cl-xs sub-bytesize sub-bytesize :read-write)
                        cl-logpdf-xs (.createDataSource claccessor (* DIM walker-count))
                        cl-logpdf-s0 (cl-sub-buffer cl-logpdf-xs
                                                    0 sub-bytesize :read-write)
                        cl-logpdf-s1 (cl-sub-buffer cl-logpdf-xs
                                                    sub-bytesize sub-bytesize
                                                    :read-write)
                        cl-accept (cl-buffer ctx (* Integer/BYTES accept-count)
                                             :read-write)
                        cl-accept-acc (cl-buffer ctx (* Long/BYTES accept-acc-count)
                                                 :read-write)
                        cl-limits (.createDataSource claccessor (* DIM 2))
                        cl-acc (.createDataSource claccessor acc-count)]
            (enq-write! queue cl-limits (buffer host-limits))
            (->GCNStretch
             ctx queue neanderthal-factory claccessor
             walker-count (work-size-1d (/ walker-count 2)) DIM WGS
             step-counter (long-array 1) (volatile! nil)
             cl-params cl-xs cl-s0 cl-s1 cl-logpdf-xs cl-logpdf-s0 cl-logpdf-s1
             cl-accept cl-accept-acc cl-acc
             (doto (kernel prog "stretch_move_accu")
               (set-args! 1 cl-params cl-s1 cl-s0 cl-logpdf-s0 cl-accept))
             (doto (kernel prog "stretch_move_accu")
               (set-args! 1 cl-params cl-s0 cl-s1 cl-logpdf-s1 cl-accept))
             (doto (kernel prog "stretch_move_bare")
               (set-args! 1 cl-params cl-s1 cl-s0 cl-logpdf-s0))
             (doto (kernel prog "stretch_move_bare")
               (set-args! 1 cl-params cl-s0 cl-s1 cl-logpdf-s1))
             (doto (kernel prog "init_walkers")
               (set-args! 1 cl-limits cl-xs))
             (doto (kernel prog "logpdf")
               (set-args! 0 cl-params cl-xs cl-logpdf-xs))
             (doto (kernel prog "sum_accept_reduction")
               (set-arg! 0 cl-accept-acc))
             (doto (kernel prog "sum_accept_reduce")
               (set-args! 0 cl-accept-acc cl-accept))
             (kernel prog "sum_means_vertical")
             (kernel prog "sum_reduction_horizontal")
             (kernel prog "sum_reduce_horizontal")
             (kernel prog "subtract_mean")
             (kernel prog "autocovariance")
             (kernel prog "min_max_reduction")
             (kernel prog "min_max_reduce")
             (kernel prog "histogram")
             (kernel prog "uint_to_real")
             (kernel prog "bitonic_local")
             (doto (kernel prog "mean_reduce")
               (set-args! 0 cl-acc cl-xs))
             (doto (kernel prog "variance_reduce")
               (set-args! 0 cl-acc cl-xs)))))
        (throw (IllegalArgumentException.
                (format "Number of walkers (%d) must be a multiple of %d."
                        walker-count (* 2 WGS))))))))

(let [reduction-src (slurp (io/resource "uncomplicate/clojurecl/kernels/reduction.cl"))
      kernels-src (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/dist-kernels.cl"))
      uniform-sample-src (slurp (io/resource "uncomplicate/bayadera/opencl/rng/uniform-sampler.cl"))
      estimate-src (slurp (io/resource "uncomplicate/bayadera/opencl/engines/amd-gcn-estimate.cl"))
      stretch-common-src (slurp (io/resource "uncomplicate/bayadera/opencl/engines/amd-gcn-stretch-generic.cl"))
      stretch-move-src (slurp (io/resource "uncomplicate/bayadera/opencl/engines/amd-gcn-stretch-move.cl"))
      compiler-options "-cl-std=CL2.0 -DLOGPDF=%s -DACCUMULATOR=float -DREAL=float -DREAL2=float2 -DPARAMS_SIZE=%d -DDIM=%d -DWGS=%d -I%s/"]

  (defn gcn-stretch-factory
    ([ctx cqueue tmp-dir-name neanderthal-factory model WGS]
     (let-release [prog-src (program-with-source
                             ctx (op [uniform-sample-src reduction-src]
                                     (source model)
                                     [kernels-src estimate-src stretch-common-src stretch-move-src]))]
       (->GCNStretchFactory
        ctx cqueue neanderthal-factory
        (build-program! prog-src
                        (format compiler-options
                                (mcmc-logpdf model) (params-size model)
                                (dimension model) WGS tmp-dir-name)
                        nil)
        (dimension model) WGS)))
    ([ctx cqueue neanderthal-factory model]
     (let [tmp-dir-name (get-tmp-dir-name)]
       (with-philox tmp-dir-name
         (gcn-stretch-factory ctx cqueue tmp-dir-name neanderthal-factory model 256))))))
