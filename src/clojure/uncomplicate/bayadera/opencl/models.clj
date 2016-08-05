(ns ^{:author "Dragan Djuric"}
    uncomplicate.bayadera.opencl.models
  (:require [clojure.java.io :as io]
            [uncomplicate.commons.core :refer [Releaseable release]]
            [uncomplicate.neanderthal
             [core :as nc]
             [protocols :as np]
             [native :refer [sge]]]
            [uncomplicate.bayadera
             [protocols :refer :all]]))

(defprotocol CLModel
  (source [this])
  (sampler-source [this]))

;; ==================== Likelihood model ====================================

(deftype CLLikelihoodModel [name loglik-name ^long lik-params-size model-source]
  Releaseable
  (release [_]
    true)
  Model
  (params-size [_]
    lik-params-size)
  CLModel
  (source [_]
    model-source)
  (sampler-source [_]
    nil)
  LikelihoodModel
  (loglik [_]
    loglik-name))

(defn cl-likelihood-model
  [source & {:keys [name loglik params-size]
             :or {name (str (gensym "likelihood"))
                  loglik (format "%s_loglik" name)
                  params-size 1}}]
  (->CLLikelihoodModel name loglik params-size
                       (if (sequential? source) source [source])))

;; ==================== Distribution model ====================================

(deftype CLDistributionModel [name logpdf-name mcmc-logpdf-name
                              ^long dist-dimension ^long dist-params-size
                              model-limits model-source sampler-kernels]
  Releaseable
  (release [_]
    (release model-limits))
  Model
  (params-size [_]
    dist-params-size)
  DistributionModel
  (logpdf [_]
    logpdf-name)
  (mcmc-logpdf [_]
    mcmc-logpdf-name)
  (dimension [_]
    dist-dimension)
  (limits [_]
    model-limits)
  CLModel
  (source [_]
    model-source)
  (sampler-source [_]
    sampler-kernels)
  ModelProvider
  (model [this]
    this))

(defn cl-distribution-model
  [source & {:keys [name logpdf mcmc-logpdf dimension params-size
                    limits sampler-source]
             :or {name (str (gensym "distribution"))
                  logpdf (format "%s_logpdf" name)
                  mcmc-logpdf logpdf dimension 1 params-size 1}}]
  (->CLDistributionModel name logpdf mcmc-logpdf dimension params-size limits
                         (if (sequential? source)
                           source
                           [source])
                         (if (sequential? sampler-source)
                           sampler-source
                           [sampler-source])))

;; ==================== Posterior model ====================================

(deftype CLPosteriorModel [name logpdf-name mcmc-logpdf-name
                           ^long dist-dimension ^long dist-params-size
                           model-limits model-source likelihood-model]
  Releaseable
  (release [_]
    (release likelihood-model))
  Model
  (params-size [_]
    dist-params-size)
  DistributionModel
  (logpdf [_]
    logpdf-name)
  (mcmc-logpdf [_]
    mcmc-logpdf-name)
  (dimension [_]
    dist-dimension)
  (limits [_]
    model-limits)
  LikelihoodModel
  (loglik [_]
    (loglik likelihood-model))
  CLModel
  (source [_]
    model-source)
  (sampler-source [_]
    nil)
  ModelProvider
  (model [this]
    this))

(defn cl-likelihood-model
  [source & {:keys [name loglik params-size]
             :or {name (str (gensym "likelihood"))
                  loglik (format "%s_loglik" name)
                  params-size 1}}]
  (->CLLikelihoodModel name loglik params-size
                       (if (sequential? source) source [source])))

;; ================ Posterior multimethod implementations ======================

(let [post-source (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/posterior.cl"))]

  (defn cl-posterior-model [prior name lik]
    (let [post-name (str (gensym name))
          post-logpdf (format "%s_logpdf" post-name)
          post-mcmc-logpdf (format "%s_mcmc_logpdf" post-name)
          post-params-size (+ (long (params-size lik))
                              (long (params-size prior)))]
      (->CLPosteriorModel post-name post-logpdf post-mcmc-logpdf
                          (dimension prior) post-params-size
                          (limits prior)
                          (conj (vec (distinct (into (source prior) (source lik))))
                                (format "%s\n%s"
                                        (format post-source post-logpdf
                                                (loglik lik) (logpdf prior)
                                                (params-size lik))
                                        (format post-source post-mcmc-logpdf
                                                (loglik lik) (mcmc-logpdf prior)
                                                (params-size lik))))
                          lik))))

(extend CLDistributionModel
  PriorModel
  {:posterior-model cl-posterior-model})

(extend CLPosteriorModel
  PriorModel
  {:posterior-model cl-posterior-model})

;; ==================== Distribution Models ====================================

(let [src (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/gaussian.h"))
      sampler-src (slurp (io/resource "uncomplicate/bayadera/opencl/rng/gaussian-sampler.cl"))]

  (def gaussian-model
    (cl-distribution-model src
                           :name "gaussian" :params-size 2
                           :limits (sge 2 1 [(- Float/MAX_VALUE) Float/MAX_VALUE])
                           :sampler-source sampler-src))

  (defn gaussian-likelihood [n]
    (cl-likelihood-model src :name "gaussian" :params-size n)))

(let [src (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/t.h"))]

  (def t-model
    (cl-distribution-model src
                           :name "t" :params-size 4
                           :limits (sge 2 1 [(- Float/MAX_VALUE) Float/MAX_VALUE])))

  (defn t-likelihood [n]
    (cl-likelihood-model src :name "t" :params-size n)))

(def uniform-model
  (cl-distribution-model (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/uniform.h"))
                         :name "uniform" :params-size 2
                         :limits (sge 2 1 [(- Float/MAX_VALUE) Float/MAX_VALUE])
                         :sampler-source
                         (slurp (io/resource "uncomplicate/bayadera/opencl/rng/uniform-sampler.cl"))))

(def beta-model
  (cl-distribution-model (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/beta.h"))
                         :name "beta" :mcmc-logpdf "beta_mcmc_logpdf" :params-size 3
                         :limits (sge 2 1 [0.0 1.0])))

(def exponential-model
  (cl-distribution-model (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/exponential.h"))
                         :name "exponential" :params-size 1
                         :limits (sge 2 1 [Float/MIN_VALUE Float/MAX_VALUE])
                         :sampler-source
                         (slurp (io/resource "uncomplicate/bayadera/opencl/rng/exponential-sampler.cl"))))

(def gamma-model
  (cl-distribution-model (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/gamma.h"))
                         :name "gamma" :params-size 2
                         :limits (sge 2 1 [0.0 Float/MAX_VALUE])))

(let [src (slurp (io/resource "uncomplicate/bayadera/opencl/distributions/binomial.h"))]
  (def binomial-model
    (cl-distribution-model src
                           :name "binomial" :mcmc-logpdf "binomial_mcmc_logpdf" :params-size 3
                           :limits (sge 2 1 [0.0 Float/MAX_VALUE])))

  (def binomial-likelihood
    (cl-likelihood-model src :name "binomial" :params-size 2)))
