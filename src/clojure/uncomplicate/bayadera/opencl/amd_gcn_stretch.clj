(ns ^{:author "Dragan Djuric"}
    uncomplicate.bayadera.opencl.amd-gcn-stretch
  (:require [clojure.java.io :as io]
            [uncomplicate.commons.core
             :refer [Releaseable release wrap-float wrap-int with-release]]
            [uncomplicate.clojurecl
             [core :refer :all]
             [toolbox :refer [count-work-groups enq-reduce enq-read-long]]]
            [uncomplicate.neanderthal
             [math :refer [sqrt]]
             [core :refer [dim create]]
             [real :refer [sum]]
             [block :refer [buffer]]
             [opencl :refer [gcn-single]]]
            [uncomplicate.bayadera.protocols :refer :all]
            [uncomplicate.bayadera.opencl
             [utils :refer [with-philox get-tmp-dir-name]]]))

(defn ^:private inc! [^ints a]
  (doto a (aset 0 (inc (aget a 0)))))

(deftype GCNStretch1D [ctx cqueue neanderthal-factory
                       ^long walker-count wsize ^long WGS
                       ^ints step-counter
                       cl-params cl-xs cl-s0 cl-s1
                       cl-logpdf-xs cl-logpdf-s0 cl-logpdf-s1
                       cl-accept cl-accept-acc
                       stretch-move-odd-kernel stretch-move-even-kernel
                       stretch-move-odd-bare-kernel stretch-move-even-bare-kernel
                       init-walkers-kernel logpdf-kernel
                       sum-accept-reduction-kernel sum-accept-kernel
                       sum-means-kernel subtract-mean-kernel
                       autocovariance-kernel]
  Releaseable
  (release [_]
    (and
     (release cl-xs)
     (release cl-s0)
     (release cl-s1)
     (release cl-logpdf-xs)
     (release cl-logpdf-s0)
     (release cl-logpdf-s1)
     (release cl-accept)
     (release cl-accept-acc)
     (release stretch-move-odd-kernel)
     (release stretch-move-even-kernel)
     (release stretch-move-odd-bare-kernel)
     (release stretch-move-even-bare-kernel)
     (release init-walkers-kernel)
     (release logpdf-kernel)
     (release sum-accept-reduction-kernel)
     (release sum-accept-kernel)
     (release sum-means-kernel)
     (release subtract-mean-kernel)
     (release autocovariance-kernel)))
  MCMCStretch
  (move! [this]
    (do
      (set-arg! stretch-move-odd-kernel 8 step-counter)
      (set-arg! stretch-move-even-kernel 8 step-counter)
      (enq-nd! cqueue stretch-move-odd-kernel wsize)
      (enq-nd! cqueue stretch-move-even-kernel wsize)
      (inc! step-counter)
      cl-xs))
  (move-bare! [this]
    (do
      (set-arg! stretch-move-odd-bare-kernel 6 step-counter)
      (set-arg! stretch-move-even-bare-kernel 6 step-counter)
      (enq-nd! cqueue stretch-move-odd-bare-kernel wsize)
      (enq-nd! cqueue stretch-move-even-bare-kernel wsize)
      (inc! step-counter)
      cl-xs))
  (acc-rate [_]
    (if (pos? (aget step-counter 0))
      (do
        (enq-reduce cqueue sum-accept-kernel sum-accept-reduction-kernel
                    WGS (count-work-groups WGS (/ walker-count 2)))
        (/ (double (enq-read-long cqueue cl-accept-acc))
           (* walker-count (aget step-counter 0))))
      Double/NaN))
  (acor [_ sample]
    (let [n (dim sample)
          min-fac 16
          MINLAG 4
          WINMULT 16
          TAUMAX 16
          lag (max MINLAG (min (quot n min-fac) WGS))
          i-max (- n lag)
          autocov-count (count-work-groups WGS n)
          sample-mean (/ (float (sum sample)) n)]
      (if (<= (* lag min-fac) n)
        (with-release [c0-vec (create neanderthal-factory autocov-count)
                       d-vec (create neanderthal-factory autocov-count)]
          (set-args! subtract-mean-kernel 0 (buffer sample)
                     (wrap-float sample-mean))
          (enq-nd! cqueue subtract-mean-kernel (work-size-1d n))
          (set-args! autocovariance-kernel 0 (wrap-int lag) (buffer c0-vec)
                     (buffer d-vec) (buffer sample) (wrap-int i-max))
          (enq-nd! cqueue autocovariance-kernel (work-size-1d n))
          (let [d (float (sum d-vec))]
            (->Autocorrelation (/ d (float (sum c0-vec))) sample-mean
                               (sqrt (/ d i-max n))
                               (* n walker-count) n walker-count lag 0.0)))
        (throw (IllegalArgumentException.
                (format (str "The autocorrelation time is too long relative to the variance."
                             "Number of steps (%d) must not be less than %d.")
                        n (* lag min-fac)))))))
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
  (sample! [this res]
    (let [res (if (number? res) (create neanderthal-factory res) res)
          res-buff (buffer res)
          n (dim res)
          available (* Float/BYTES walker-count)]
      (do
        (loop [ofst 0 requested (* Float/BYTES (long n))]
          (do (move-bare! this)
              (if (<= requested available)
                (enq-copy! cqueue cl-xs res-buff 0 ofst requested nil nil)
                (do
                  (enq-copy! cqueue cl-xs res-buff 0 ofst available nil nil)
                  (recur (+ ofst available) (- requested available))))))
        res)))
  MCMC
  (set-position! [this position]
    (do
      (if (cl-buffer? position)
        (enq-copy! cqueue position cl-xs)
        (let [seed (wrap-int position)]
          (set-arg! init-walkers-kernel 0 seed)
          (enq-nd! cqueue init-walkers-kernel (work-size-1d (/ walker-count 4)))))
      (enq-nd! cqueue logpdf-kernel (work-size-1d walker-count))
      this))
  (burn-in! [this n a]
    (do
      (aset step-counter 0 0)
      (set-arg! stretch-move-odd-bare-kernel 5 a)
      (set-arg! stretch-move-even-bare-kernel 5 a)
      (dotimes [i (dec (long n))]
        (move-bare! this))
      (let [means-count (long (count-work-groups WGS (/ walker-count 2)))]
        (with-release [cl-means (cl-buffer ctx (* Float/BYTES) :read-write)]
          (aset step-counter 0 0)
          (enq-fill! cqueue cl-accept (int-array 1))
          (set-arg! stretch-move-odd-kernel 6 cl-means)
          (set-arg! stretch-move-even-kernel 6 cl-means)
          (set-arg! stretch-move-odd-kernel 7 a)
          (set-arg! stretch-move-even-kernel 7 a)
          (move! this)
          (acc-rate this)))))
  (run-sampler! [this n a]
    (let [means-count (long (count-work-groups WGS (/ walker-count 2)))]
      (with-release [means-vec (create neanderthal-factory n)
                     cl-means (cl-buffer ctx (* Float/BYTES means-count (long n))
                                         :read-write)]
        (aset step-counter 0 0)
        (enq-fill! cqueue cl-means (float-array 1))
        (set-arg! stretch-move-odd-kernel 6 cl-means)
        (set-arg! stretch-move-even-kernel 6 cl-means)
        (set-arg! stretch-move-odd-kernel 7 a)
        (set-arg! stretch-move-even-kernel 7 a)
        (dotimes [i n]
          (move! this))
        (set-args! sum-means-kernel 0 (buffer means-vec)
                   cl-means (wrap-int means-count))
        (enq-nd! cqueue sum-means-kernel (work-size-1d n))
        (assoc (acor this means-vec) :acc-rate  (acc-rate this))))))

(deftype GCNStretch1DFactory [ctx queue neanderthal-factory prog ^long WGS]
  Releaseable
  (release [_]
    (release prog))
  MCMCFactory
  (mcmc-sampler [_ walker-count cl-params low high]
    (let [walker-count (long walker-count)]
      (if (and (<= (* 2 WGS) walker-count) (zero? (rem walker-count (* 2 WGS))))
        (let [cnt (long (/ walker-count 2))
              accept-count (count-work-groups WGS cnt)
              accept-acc-count (count-work-groups WGS accept-count)
              bytecount (long (* Float/BYTES cnt))
              step-counter (int-array 1)
              params-buffer (buffer cl-params)
              cl-xs (cl-buffer ctx (* 2 bytecount) :read-write)
              cl-s0 (cl-sub-buffer cl-xs 0 bytecount :read-write)
              cl-s1 (cl-sub-buffer cl-xs bytecount bytecount :read-write)
              cl-logpdf-xs (cl-buffer ctx (* 2 bytecount) :read-write)
              cl-logpdf-s0 (cl-sub-buffer cl-logpdf-xs 0 bytecount :read-write)
              cl-logpdf-s1 (cl-sub-buffer cl-logpdf-xs bytecount bytecount :read-write)
              cl-accept (cl-buffer ctx (* Integer/BYTES accept-count) :read-write)
              cl-accept-acc (cl-buffer ctx (* Long/BYTES accept-acc-count) :read-write)]
          (->GCNStretch1D
           ctx queue neanderthal-factory
           walker-count (work-size-1d (/ walker-count 2)) WGS step-counter
           cl-params cl-xs cl-s0 cl-s1 cl-logpdf-xs cl-logpdf-s0 cl-logpdf-s1
           cl-accept cl-accept-acc
           (doto (kernel prog "stretch_move1_accu")
             (set-args! 1 params-buffer cl-s1 cl-s0 cl-logpdf-s0 cl-accept))
           (doto (kernel prog "stretch_move1_accu")
             (set-args! 1 params-buffer cl-s0 cl-s1 cl-logpdf-s1 cl-accept))
           (doto (kernel prog "stretch_move1_bare")
             (set-args! 1 params-buffer cl-s1 cl-s0 cl-logpdf-s0))
           (doto (kernel prog "stretch_move1_bare")
             (set-args! 1 params-buffer cl-s0 cl-s1 cl-logpdf-s1))
           (doto (kernel prog "init_walkers")
             (set-args! 1 (wrap-float low) (wrap-float high) cl-xs))
           (doto (kernel prog "logpdf")
             (set-args! 0 params-buffer cl-xs cl-logpdf-xs))
           (doto (kernel prog "sum_accept_reduction")
             (set-arg! 0 cl-accept-acc))
           (doto (kernel prog "sum_accept_reduce")
             (set-args! 0 cl-accept-acc cl-accept))
           (kernel prog "sum_means")
           (kernel prog "subtract_mean")
           (kernel prog "autocovariance")))
        (throw (IllegalArgumentException.
                (format "Number of walkers (%d) must be a multiple of %d."
                        walker-count (* 2 WGS))))))))

(let [reduction-src (slurp (io/resource "uncomplicate/clojurecl/kernels/reduction.cl"))
      kernels-src (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/dist-kernels.cl"))
      uniform-sample-src (slurp (io/resource "uncomplicate/bayadera/opencl/rng/uniform-sampler.cl"))
      stretch-move-src (slurp (io/resource "uncomplicate/bayadera/opencl/mcmc/amd-gcn-stretch-move.cl"))]

  (defn gcn-stretch-1d-factory
    ([ctx cqueue tmp-dir-name neanderthal-factory model WGS]
     (->GCNStretch1DFactory
      ctx cqueue neanderthal-factory
      (build-program!
       (program-with-source
        ctx
        (into [uniform-sample-src
               reduction-src]
              (into (source model) [kernels-src stretch-move-src])))
       (format "-cl-std=CL2.0 -DLOGPDF=%s -DACCUMULATOR=float -DPARAMS_SIZE=%d -DWGS=%d -I%s/"
               (mcmc-logpdf model) (params-size model) WGS tmp-dir-name)
       nil)
      WGS))
    ([ctx cqueue neanderthal-factory model]
     (let [tmp-dir-name (get-tmp-dir-name)]
       (with-philox tmp-dir-name
         (gcn-stretch-1d-factory ctx cqueue tmp-dir-name neanderthal-factory model 256))))))
