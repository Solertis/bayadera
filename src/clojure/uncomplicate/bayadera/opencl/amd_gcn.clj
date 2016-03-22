(ns uncomplicate.bayadera.opencl.amd-gcn
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as fsc]
            [uncomplicate.clojurecl.core :refer :all]
            [uncomplicate.neanderthal
             [core :refer [dim sum nrm2]]
             [protocols :refer [BlockCreator]]
             [opencl :refer [clv clge]]]
            [uncomplicate.neanderthal.opencl.amd-gcn :refer [gcn-single]]
            [uncomplicate.bayadera.protocols :refer :all]))

(deftype GCNDirectSampler [cqueue sample-kernel]
  Releaseable
  (release [_]
    (release sample-kernel))
  RandomSampler
  (sample! [this seed params res]
    (do
      (set-args! sample-kernel (int-array [seed]) (.buffer params) (.buffer res))
      (enq-nd! cqueue sample-kernel (work-size [(dim res)]))
      this)))

(deftype GCNDistributionEngine [cqueue logpdf-kernel pdf-kernel]
  Releaseable
  (release [_]
    (release logpdf-kernel)
    (release pdf-kernel))
  DistributionEngine
  (logpdf! [this params x res]
    (do
      (set-args! logpdf-kernel (.buffer params) (.buffer x) (.buffer res))
      (enq-nd! cqueue logpdf-kernel (work-size [(dim x)]))
      this))
  (pdf! [this params x res]
    (do
      (set-args! pdf-kernel (.buffer params) (.buffer x) (.buffer res))
      (enq-nd! cqueue pdf-kernel (work-size [(dim x)]))
      this)))

(defrecord GCNDataSetEngine [cqueue variance-kernel]
  Releaseable
  (release [_]
    (release variance-kernel))
  Spread
  (variance [_ dataset]
    #_(let [dataset-mean (mean dataset)
            data (data dataset)
            WGS (.WGS neand-factory)]
        (set-arg! variance-kernel (.acc neand-factory)
                  (.buffer (data dataset)))
        (enq-reduce variance-kernel sum-reduction-kernel WGS (dim data)))
    (let [sx (sum (data dataset))
          nrm (nrm2 (data dataset))
          n (dim (data dataset))]
      (/ (- (* nrm nrm) (/ (* sx sx ) n)) n))))

(deftype GCNEngineFactory [cqueue neand-factory prog]
  Releaseable
  (release [_]
    (release prog)
    (release neand-factory))
  BlockCreator
  (create-block [_ n]
    (clv neand-factory n))
  (create-block [_ m n]
    (clge neand-factory m n))
  EngineFactory
  (dataset-engine [_ data-vect]
    (->GCNDataSetEngine cqueue (kernel prog "gaussian_sample")));;TODO variance
  (random-sampler [_ dist-name]
    (->GCNDirectSampler cqueue (kernel prog (str dist-name "_sample"))))
  (distribution-engine [_ dist-name]
    (->GCNDistributionEngine cqueue
                             (kernel prog (str dist-name "_logpdf_kernel"))
                             (kernel prog (str dist-name "_pdf_kernel")))))

(defn ^:private copy-random123 [include-name tmp-dir-name]
  (io/copy
   (io/input-stream
    (io/resource (format "uncomplicate/bayadera/rng/opencl/include/Random123/%s"
                         include-name)))
   (io/file (format "%s/Random123/%s" tmp-dir-name include-name))))

(defn gcn-engine-factory [ctx cqueue]
  (let [tmp-dir-name (fsc/temp-dir "uncomplicate/")]
    (try
      (fsc/mkdirs (format "%s/%s" tmp-dir-name "Random123/features/"))
      (doseq [res-name ["philox.h" "array.h" "features/compilerfeatures.h"
                        "features/openclfeatures.h" "features/sse.h"]]
        (copy-random123 res-name tmp-dir-name))
      (let [neand-factory (gcn-single ctx cqueue)]
             (->GCNEngineFactory
              cqueue neand-factory
              (build-program!
               (program-with-source
                ctx
                [(slurp (io/resource "uncomplicate/bayadera/distributions/opencl/sampling.h"))
                 (slurp (io/resource "uncomplicate/bayadera/distributions/opencl/measures.h"))
                 (slurp (io/resource "uncomplicate/bayadera/distributions/opencl/kernels.cl"))
                 ;;TODO(slurp (io/resource "uncomplicate/bayadera/dataset/opencl/amd-gcn.cl"))
                 ])
               (format "-cl-std=CL2.0 -I%s/" tmp-dir-name)
               nil)))
           (finally
             (fsc/delete-dir tmp-dir-name)))))
