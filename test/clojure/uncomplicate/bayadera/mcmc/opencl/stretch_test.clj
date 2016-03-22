(ns uncomplicate.bayadera.mcmc.opencl.stretch-test
  (:require [midje.sweet :refer :all]
            [clojure.core.async :refer [chan <!!]]
            [uncomplicate.clojurecl
             [core :refer :all]]
            [uncomplicate.bayadera.protocols :refer :all]
            [uncomplicate.bayadera.mcmc.opencl.amd-gcn-stretch :refer :all]))

(with-release [dev (first (devices (first (platforms))))
               ctx (context [dev])
               cqueue (command-queue ctx dev :profiling)]
  (facts
   "Test for MCMC stretch engine."
   (let [walker-count (long (Math/pow 2 13))
         params (float-array [200 1])
         xs (float-array walker-count)
         run-cnt 1000]
     (with-release [mcmc-engine-factory (gcn-stretch-1d-engine-factory ctx cqueue)
                    engine (mcmc-engine mcmc-engine-factory walker-count params)]
       (init! engine)
       (/ (reduce + (run-sampler! engine run-cnt)) run-cnt) => :mean
       (/ (reduce + (run-sampler! engine run-cnt)) run-cnt) => :mean
       (/ (reduce + (run-sampler! engine run-cnt)) run-cnt) => :mean
       (enq-read! cqueue
                  (.cl-xs engine)
                  xs)
       => cqueue
       (/ (reduce + xs) walker-count) => :mean-xs
       ;;(frequencies (map #(Math/round (* % 10)) xs)) => :Xk
       (< 0.75 (acc-rate engine) 0.80) => true

                  ))))
