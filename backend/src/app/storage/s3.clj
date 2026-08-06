;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.storage.s3
  "S3 Storage backend implementation."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.storage :as-alias sto]
   [app.storage.impl :as impl]
   [app.storage.tmp :as tmp]
   [app.worker :as-alias wrk]
   [clojure.java.io :as io]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.core :as p])
  (:import
   java.io.FilterInputStream
   java.io.InputStream
   java.net.URI
   java.nio.file.Path
   java.time.Duration
   java.util.Collection
   java.util.concurrent.atomic.AtomicLong
   software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
   software.amazon.awssdk.core.async.AsyncRequestBody
   software.amazon.awssdk.core.async.AsyncResponseTransformer
   software.amazon.awssdk.core.ResponseBytes
   software.amazon.awssdk.auth.signer.S3SignerExecutionAttribute
   software.amazon.awssdk.core.client.config.ClientAsyncConfiguration
   software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
   software.amazon.awssdk.core.interceptor.Context$AfterTransmission
   software.amazon.awssdk.core.interceptor.Context$BeforeTransmission
   software.amazon.awssdk.core.interceptor.Context$ModifyHttpRequest
   software.amazon.awssdk.core.interceptor.ExecutionAttributes
   software.amazon.awssdk.core.interceptor.ExecutionInterceptor
   software.amazon.awssdk.http.SdkHttpRequest
   software.amazon.awssdk.http.SdkHttpResponse
   software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
   software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup
   software.amazon.awssdk.regions.Region
   software.amazon.awssdk.services.s3.model.Delete
   software.amazon.awssdk.services.s3.model.DeleteObjectRequest
   software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
   software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
   software.amazon.awssdk.services.s3.model.GetObjectRequest
   software.amazon.awssdk.services.s3.model.NoSuchKeyException
   software.amazon.awssdk.services.s3.model.ObjectIdentifier
   software.amazon.awssdk.services.s3.model.PutObjectRequest
   software.amazon.awssdk.services.s3.model.S3Error
   software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
   software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
   software.amazon.awssdk.services.s3.presigner.S3Presigner
   software.amazon.awssdk.services.s3.S3AsyncClient
   software.amazon.awssdk.services.s3.S3AsyncClientBuilder
   software.amazon.awssdk.services.s3.S3Configuration))

(def ^:private max-retries
  "A maximum number of retries on internal operations"
  3)

(def ^:private max-concurrency
  "Maximum concurrent request to S3 service"
  128)

(def ^:private max-pending-connection-acquires
  20000)

(def default-timeout
  (ct/duration {:seconds 30}))

(declare put-object)
(declare get-object-bytes)
(declare get-object-data)
(declare get-object-url)
(declare del-object)
(declare del-object-in-bulk)
(declare build-s3-client)
(declare build-s3-presigner)

;; (set! *warn-on-reflection* true)
;; (set! *unchecked-math* :warn-on-boxed)

;; --- BACKEND INIT

(def ^:private schema:config
  [:map {:title "s3-backend-config"}
   ::wrk/netty-io-executor
   [::region {:optional true} :keyword]
   [::bucket {:optional true} ::sm/text]
   [::prefix {:optional true} ::sm/text]
   [::endpoint {:optional true} ::sm/uri]])

(defmethod ig/expand-key ::backend
  [k v]
  {k (merge {::region :eu-central-1} (d/without-nils v))})

(defmethod ig/assert-key ::backend
  [_ params]
  (assert (sm/check schema:config params)))

(defmethod ig/init-key ::backend
  [_ params]
  (when (and (contains? params ::region)
             (contains? params ::bucket))
    (let [client    (build-s3-client params)
          presigner (build-s3-presigner params)]
      (assoc params
             ::sto/type :s3
             ::counter (AtomicLong. 0)
             ::client @client
             ::presigner presigner
             ::close-fn #(.close ^java.lang.AutoCloseable client)))))

(defmethod ig/resolve-key ::backend
  [_ params]
  (dissoc params ::close-fn))

(defmethod ig/halt-key! ::backend
  [_ {:keys [::close-fn]}]
  (when (fn? close-fn)
    (close-fn)))

(def ^:private schema:backend
  [:map {:title "s3-backend"}
   ;; [::region :keyword]
   ;; [::bucket ::sm/text]
   [::client [:fn #(instance? S3AsyncClient %)]]
   [::presigner [:fn #(instance? S3Presigner %)]]
   [::prefix {:optional true} ::sm/text]
   #_[::sto/type [:= :s3]]])

(sm/register! ::backend schema:backend)

(def ^:private valid-backend?
  (sm/validator schema:backend))

;; --- API IMPL

(defmethod impl/put-object :s3
  [backend object content]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (p/await! (put-object backend object content)))

(defmethod impl/get-object-data :s3
  [backend object]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (loop [result (get-object-data backend object)
         retryn 0]

    (let [result (p/await result)]
      (if (ex/exception? result)
        (cond
          (ex/instance? NoSuchKeyException result)
          (ex/raise :type :not-found
                    :code :object-not-found
                    :hint "s3 object not found"
                    :object-id (:id object)
                    :object-path (impl/id->path (:id object))
                    :cause result)

          (and (ex/instance? java.nio.file.FileAlreadyExistsException result)
               (< retryn max-retries))
          (recur (get-object-data backend object)
                 (inc retryn))

          :else
          (throw result))

        result))))

(defmethod impl/get-object-bytes :s3
  [backend object]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (p/await! (get-object-bytes backend object)))

(defmethod impl/get-object-url :s3
  [backend object options]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (get-object-url backend object options))

(defmethod impl/del-object :s3
  [backend object]
  (p/await! (del-object backend object)))

(defmethod impl/del-objects-in-bulk :s3
  [backend ids]
  (assert (valid-backend? backend) "expected a valid backend instance")
  (p/await! (del-object-in-bulk backend ids)))

;; --- HELPERS

(defn- lookup-region
  ^Region
  [region]
  (Region/of (name region)))

(defn- s3-debug-interceptor
  "AWS SDK ExecutionInterceptor that:
   - Sets ENABLE_PAYLOAD_SIGNING=false for custom endpoints (avoids XAmzContentSHA256Mismatch)
   - Logs key request/response details for diagnosing S3 compatibility issues."
  [use-custom-endpoint?]
  (reify ExecutionInterceptor
    ;; --- modifyHttpRequest: runs BEFORE signing, sets execution attributes ---
    (^SdkHttpRequest modifyHttpRequest [_ ^Context$ModifyHttpRequest ctx ^ExecutionAttributes attrs]
      (when use-custom-endpoint?
        (.putAttribute attrs S3SignerExecutionAttribute/ENABLE_PAYLOAD_SIGNING false)
        (.putAttribute attrs S3SignerExecutionAttribute/ENABLE_CHUNKED_ENCODING false))
      (.httpRequest ctx))

    ;; --- beforeTransmission: runs AFTER signing, logs what's actually sent ---
    (^void beforeTransmission [_ ^Context$BeforeTransmission ctx ^ExecutionAttributes attrs]
      (let [req ^SdkHttpRequest (.httpRequest ctx)]
        (l/dbg :hint "s3 request ->"
               :method (str (.method req))
               :uri (str (.getUri req))
               :x-amz-content-sha256 (some-> (.firstMatchingHeader req "x-amz-content-sha256")
                                             (str))
               :content-type (some-> (.firstMatchingHeader req "Content-Type")
                                     (str))
               :content-length (some-> (.firstMatchingHeader req "Content-Length")
                                       (str))
               :transfer-encoding (some-> (.firstMatchingHeader req "Transfer-Encoding")
                                          (str))
               :amz-checksum (some-> (.firstMatchingHeader req "x-amz-checksum-sha256")
                                     (str)))))
    (^void afterTransmission [_ ^Context$AfterTransmission ctx ^ExecutionAttributes attrs]
      (let [rsp ^SdkHttpResponse (.httpResponse ctx)]
        (l/dbg :hint "s3 response <-"
               :status (.statusCode rsp)
               :status-text (some-> (.statusText rsp) (str))
               :x-amz-request-id (some-> (.firstMatchingHeader rsp "x-amz-request-id")
                                         (str)))))
    (^void onExecutionFailure [_ ^software.amazon.awssdk.core.interceptor.Context$FailedExecution ctx ^ExecutionAttributes attrs]
      (let [cause (.exception ctx)]
        (l/error :hint "s3 execution failure"
                 :error-message (ex-message cause)
                 :error-type (str (type cause)))))))

(defn- build-s3-client
  [{:keys [::region ::endpoint ::wrk/netty-io-executor] :as params}]
  (let [creds-provider (DefaultCredentialsProvider/create)
        aconfig  (-> (ClientAsyncConfiguration/builder)
                     (.build))

        sconfig  (-> (S3Configuration/builder)
                     (cond-> (some? endpoint) (.pathStyleAccessEnabled true))
                     (.build))

        hclient  (-> (NettyNioAsyncHttpClient/builder)
                     (.eventLoopGroup (SdkEventLoopGroup/create netty-io-executor))
                     (.connectionAcquisitionTimeout default-timeout)
                     (.connectionTimeout default-timeout)
                     (.readTimeout default-timeout)
                     (.writeTimeout default-timeout)
                     (.maxConcurrency (int max-concurrency))
                     (.maxPendingConnectionAcquires (int max-pending-connection-acquires))
                     (.build))

        use-custom-endpoint? (some? endpoint)
        _ (when use-custom-endpoint?
            (l/info :hint "s3 custom endpoint detected - will configure UNSIGNED-PAYLOAD"
                    :endpoint (str endpoint)
                    :region (str region)))

        client   (let [builder (S3AsyncClient/builder)
                       builder (.serviceConfiguration ^S3AsyncClientBuilder builder ^S3Configuration sconfig)
                       builder (.asyncConfiguration ^S3AsyncClientBuilder builder ^ClientAsyncConfiguration aconfig)
                       builder (.httpClient ^S3AsyncClientBuilder builder ^NettyNioAsyncHttpClient hclient)
                       builder (.region ^S3AsyncClientBuilder builder (lookup-region region))
                       builder (.credentialsProvider ^S3AsyncClientBuilder builder creds-provider)
                       ;; Always add the debug interceptor. For custom endpoints,
                       ;; it also disables payload signing and chunked encoding
                       ;; via S3SignerExecutionAttribute (set in modifyHttpRequest
                       ;; before signing) to avoid XAmzContentSHA256Mismatch.
                       builder (.overrideConfiguration ^S3AsyncClientBuilder builder
                                 (-> (ClientOverrideConfiguration/builder)
                                     (.addExecutionInterceptor (s3-debug-interceptor use-custom-endpoint?))
                                     (.build)))
                       builder (cond-> ^S3AsyncClientBuilder builder
                                 use-custom-endpoint?
                                 (.endpointOverride (URI. (str endpoint))))]
                   (.build ^S3AsyncClientBuilder builder))]

    (l/info :hint "s3 client created"
            :region (str region)
            :custom-endpoint? use-custom-endpoint?
            :payload-signing-disabled? use-custom-endpoint?)

    (reify
      clojure.lang.IDeref
      (deref [_] client)

      java.lang.AutoCloseable
      (close [_]
        (.close ^NettyNioAsyncHttpClient hclient)
        (.close ^S3AsyncClient client)))))

(defn- build-s3-presigner
  [{:keys [::region ::endpoint]}]
  (let [creds-provider (DefaultCredentialsProvider/create)
        config (-> (S3Configuration/builder)
                   (cond-> (some? endpoint) (.pathStyleAccessEnabled true))
                   (.build))]

    (-> (S3Presigner/builder)
        (cond-> (some? endpoint) (.endpointOverride (URI. (str endpoint))))
        (.region (lookup-region region))
        (.serviceConfiguration ^S3Configuration config)
        (.credentialsProvider creds-provider)
        (.build))))

(defn- make-request-body
  "Creates an AsyncRequestBody from content object.
  Reads entire content into bytes first to ensure consistent SHA256 calculation.
  Logs size comparison for debugging potential SHA256 mismatches."
  [counter content]
  (let [declared-size (impl/get-size content)
        input         (io/input-stream content)
        bytes         (try
                        (.readAllBytes ^InputStream input)
                        (finally
                          (.close ^InputStream input)))
        actual-size   (alength ^bytes bytes)]
    (l/dbg :hint "s3 request body prepared"
           :declared-size declared-size
           :actual-size actual-size
           :size-match? (= declared-size actual-size))
    (when (not= declared-size actual-size)
      (l/wrn :hint "s3 content size mismatch detected"
             :declared-size declared-size
             :actual-size actual-size
             :delta (- declared-size actual-size)))
    (AsyncRequestBody/fromBytes bytes)))

(defn- put-object
  [{:keys [::client ::bucket ::prefix ::counter]} {:keys [id] :as object} content]
  (let [path      (dm/str prefix (impl/id->path id))
        mdata     (meta object)
        mtype     (:content-type mdata "application/octet-stream")
        content-size (impl/get-size content)
        rbody     (make-request-body counter content)
        request   (.. (PutObjectRequest/builder)
                      (bucket bucket)
                      (contentType mtype)
                      (key path)
                      (build))]
    (l/dbg :hint "s3 put-object start"
           :object-id (str id)
           :bucket bucket
           :key path
           :content-type mtype
           :content-size content-size)
    (->> (.putObject ^S3AsyncClient client
                     ^PutObjectRequest request
                     ^AsyncRequestBody rbody)
         (p/fmap (fn [result]
                   (l/dbg :hint "s3 put-object success"
                          :object-id (str id)
                          :bucket bucket
                          :key path
                          :size content-size)
                   object))
         (p/merr (fn [cause]
                   (l/error :hint "s3 put-object failed"
                            :object-id (str id)
                            :bucket bucket
                            :key path
                            :content-type mtype
                            :content-size content-size
                            :error (ex-message cause)
                            :cause cause)
                   cause)))))

;; FIXME: research how to avoid reflection on close method
(defn- path->stream
  [path]
  (proxy [FilterInputStream] [(io/input-stream path)]
    (close []
      (ex/ignoring (fs/delete path))
      (proxy-super close))))

(defn- get-object-data
  [{:keys [::client ::bucket ::prefix]} {:keys [id size]}]
  (let [gor (.. (GetObjectRequest/builder)
                (bucket bucket)
                (key (str prefix (impl/id->path id)))
                (build))]

    ;; If the file size is greater than 2MiB then stream the content
    ;; to the filesystem and then read with buffered inputstream; if
    ;; not, read the contento into memory using bytearrays.
    (if (> ^long size (* 1024 1024 2))
      (let [path (tmp/tempfile :prefix "penpot.storage.s3." :min-age "6h")
            rxf  (AsyncResponseTransformer/toFile ^Path path)]
        (->> (.getObject ^S3AsyncClient client
                         ^GetObjectRequest gor
                         ^AsyncResponseTransformer rxf)
             (p/fmap (constantly path))
             (p/fmap path->stream)))

      (let [rxf (AsyncResponseTransformer/toBytes)]
        (->> (.getObject ^S3AsyncClient client
                         ^GetObjectRequest gor
                         ^AsyncResponseTransformer rxf)
             (p/fmap #(.asInputStream ^ResponseBytes %)))))))

(defn- get-object-bytes
  [{:keys [::client ::bucket ::prefix]} {:keys [id]}]
  (let [gor (.. (GetObjectRequest/builder)
                (bucket bucket)
                (key (str prefix (impl/id->path id)))
                (build))
        rxf (AsyncResponseTransformer/toBytes)]
    (->> (.getObject ^S3AsyncClient client
                     ^GetObjectRequest gor
                     ^AsyncResponseTransformer rxf)
         (p/fmap #(.asByteArray ^ResponseBytes %)))))

(def default-max-age
  (ct/duration {:minutes 10}))

(defn- get-object-url
  [{:keys [::presigner ::bucket ::prefix]} {:keys [id]} {:keys [max-age] :or {max-age default-max-age}}]
  (assert (ct/duration? max-age) "expected valid duration instance")

  (let [gor  (.. (GetObjectRequest/builder)
                 (bucket bucket)
                 (key (dm/str prefix (impl/id->path id)))
                 (build))
        gopr (.. (GetObjectPresignRequest/builder)
                 (signatureDuration ^Duration max-age)
                 (getObjectRequest ^GetObjectRequest gor)
                 (build))
        pgor (.presignGetObject ^S3Presigner presigner ^GetObjectPresignRequest gopr)]
    (u/uri (str (.url ^PresignedGetObjectRequest pgor)))))

(defn- del-object
  [{:keys [::bucket ::client ::prefix]} {:keys [id] :as obj}]
  (let [dor (.. (DeleteObjectRequest/builder)
                (bucket bucket)
                (key (dm/str prefix (impl/id->path id)))
                (build))]
    (->> (.deleteObject ^S3AsyncClient client ^DeleteObjectRequest dor)
         (p/fmap (constantly nil)))))

(defn- del-object-in-bulk
  [{:keys [::bucket ::client ::prefix]} ids]

  (let [oids (map (fn [id]
                    (.. (ObjectIdentifier/builder)
                        (key (str prefix (impl/id->path id)))
                        (build)))
                  ids)
        delc (.. (Delete/builder)
                 (objects ^Collection oids)
                 (build))
        dor  (.. (DeleteObjectsRequest/builder)
                 (bucket bucket)
                 (delete ^Delete delc)
                 (build))]

    (->> (.deleteObjects ^S3AsyncClient client ^DeleteObjectsRequest dor)
         (p/fmap (fn [dres]
                   (when (.hasErrors ^DeleteObjectsResponse dres)
                     (let [errors (seq (.errors ^DeleteObjectsResponse dres))]
                       (ex/raise :type :internal
                                 :code :error-on-s3-bulk-delete
                                 :s3-errors (mapv (fn [^S3Error error]
                                                    {:key (.key error)
                                                     :msg (.message error)})
                                                  errors)))))))))
