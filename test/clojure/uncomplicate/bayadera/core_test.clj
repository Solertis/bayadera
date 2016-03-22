(ns uncomplicate.bayadera.core-test
  (:require [midje.sweet :refer :all]
            [uncomplicate.clojurecl.core :refer :all]
            [uncomplicate.neanderthal
             [math :refer [log exp]]
             [core :refer [dim sum nrm2 fmap! copy transfer! dot]]
             [native :refer [sv]]]
            [uncomplicate.bayadera
             [protocols :as p]
             [core :refer :all]
             [impl :refer :all]
             [special :refer [lnbeta]]]
            [uncomplicate.bayadera.opencl.amd-gcn :refer [gcn-engine-factory]]))

(with-release [dev (first (sort-by-cl-version (devices (first (platforms)))))
               ctx (context [dev])
               cqueue (command-queue ctx dev)]
  (facts
   "Core functions for gaussian distribution."
   (let [sample-count (* 256 44 94)
         mu 200.0
         sigma 10.0]
     (with-release [engine-factory (gcn-engine-factory ctx cqueue)
                    dist (gaussian engine-factory mu sigma)
                    gaussian-sampler (sampler dist)
                    cl-sample (dataset engine-factory (sample gaussian-sampler sample-count))]

       (mean dist) => mu
       (sd dist) => sigma
       (mean cl-sample) => (roughly mu)
       (sd cl-sample) => (roughly sigma (/ sigma 100))
       (mean-variance cl-sample) => (sv (mean cl-sample) (variance cl-sample))
       (mean-sd cl-sample) => (sv (mean cl-sample) (sd cl-sample)))))


  (facts
   "Core functions for uniform distribution."
   (let [sample-count (* 256 44 94)
         a 100.0
         b 133.4]
     (with-release [engine-factory (gcn-engine-factory ctx cqueue)
                    dist (uniform engine-factory a b)
                    uniform-sampler (sampler dist)
                    cl-sample (dataset engine-factory (sample uniform-sampler sample-count))
                    cl-pdf (pdf dist cl-sample)]

       (mean cl-sample) => (roughly (mean dist))
       (sd cl-sample) => (roughly (sd dist) (/ (sd dist) 100.0))
       (mean-variance cl-sample) => (sv (mean cl-sample) (variance cl-sample))
       (mean-sd cl-sample) => (sv (mean cl-sample) (sd cl-sample))
       (/ (sum cl-pdf) (dim cl-pdf)) => (roughly (/ 1.0 (- b a))))))


  (let [sample-count (* 256 44 94)
        a 2.0
        b 5.0
        beta-pdf (fn ^double [^double x]
                   (double (exp (- (+ (* (dec a) (log x)) (* (dec b) (log (- 1.0 x))))
                                   (lnbeta a b)))))]
    (facts
     "Core functions for beta distribution."
     (with-release [engine-factory (gcn-engine-factory ctx cqueue)
                    dist (beta engine-factory a b)
                    beta-sampler (time (sampler dist))
                    cl-sample (dataset engine-factory (sample beta-sampler sample-count))
                    cl-pdf (pdf dist cl-sample)
                    host (transfer! (p/data cl-sample) (sv sample-count))]
       (mean cl-sample) => (roughly (mean dist))
       (sd cl-sample) => (roughly (sd dist) (/ (sd dist) 100.0))
       (mean-variance cl-sample) => (sv (mean cl-sample) (variance cl-sample))
       (mean-sd cl-sample) => (sv (mean cl-sample) (sd cl-sample))
       (sum cl-pdf) => (roughly (sum (time (fmap! beta-pdf host))))))))
