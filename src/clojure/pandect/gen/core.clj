(ns ^ {:doc "Code Generation for Pandect"
       :author "Yannick Scherer"}
  pandect.gen.core
  (:import [java.io File FileInputStream InputStream]))

;; ## Concept
;;
;; Instead of having types that implement protocols that perform
;; the actual digest/checksum calculations (pandect <= 0.2.1), we
;; will have a protocol for code generators to be used to create
;; the digest/checksum functions in pandect.core at compile
;; time.

;; ## Protocol

(defprotocol CodeGen
  "Protocol for Pandect Code Generators."
  (algorithm-string [this]
    "Get String representing the Algorithm this Code Generator is built
     for."))

(defprotocol HMACGen
  (bytes->hmac [this msg-form key-form]
    "Generate code to convert the byte array produced by the given `msg-form`
     to a value representing the hash-based message authentication code using the given
     `key-form` (a byte array).")
  (stream->hmac [this stream-form key-form]
    "Generate code to convert the input stream produced by the given `stream-form`
     to a value representing the hash-based message authentication code using the given
     `key-form` (a byte array).")
  (hmac->string [this form]
    "Generate code to convert the HMAC value produced by the given form to
     a hex string.")
  (hmac->bytes [this form]
    "Generate code to convert the HMAC value produced by the given form to
     a byte array."))

(defprotocol HashGen
  (bytes->hash [this form]
    "Generate code to convert the byte array produced by the given form to
     a value representing the given hash.")
  (stream->hash [this form]
    "Generate code to convert the input stream produced by the given form
     to a value representing the given hash.")
  (hash->string [this form]
    "Generate code to convert the hash value produced by the given form to
     a hex string.")
  (hash->bytes [this form]
    "Generate code to convert the hash value produced by the given form to
     a byte array."))

(defn support-hash?
  [code-gen]
  (satisfies? HashGen code-gen))

(defn support-hmac?
  [code-gen]
  (satisfies? HMACGen code-gen))

(defprotocol ByteArrayConvertable
  "Protocol for Entities that can be converted to byte arrays."
  (convert-to-byte-array [this]))

(extend-protocol ByteArrayConvertable
  (class (byte-array 0))
  (convert-to-byte-array [this] this)
  String
  (convert-to-byte-array [this] (.getBytes this))
  java.io.File
  (convert-to-byte-array [this]
    (.getBytes ^String (slurp this)))
  java.io.InputStream
  (convert-to-byte-array [this]
    (.getBytes ^String (slurp this))))

;; ## Multimethod

(defmulti code-generator
  "Get code generator for the given algorithm."
  (fn [algorithm] algorithm)
  :default nil)

(defmethod code-generator nil
  [x]
  (println "WARN: No such Code Generator: " x)
  nil)

;; ## Generation

(defn- generate-protocol-impl
  "Will generate a protocol that offers a single function generating an algorithm-
   specific hash value. Will also extend the following types to implement said
   protocol:

   - byte array
   - String
   - InputStream
   - File"
  [code-gen id hash-f hmac-f]
  (let [sym    (gensym "this")
        k      (gensym "key")
        P      (vary-meta (gensym (str id "Hash")) assoc :private true)
        hash-f (vary-meta hash-f assoc :private true)
        hmac-f (vary-meta hmac-f assoc :private true)
        gen-impl (fn [h-fn hm-fn msg-form]
                   (concat
                     (when (support-hash? code-gen)
                       [`(~hash-f [~sym] ~(h-fn code-gen msg-form))])
                     (when (support-hmac? code-gen)
                       [`(~hmac-f [~sym ~k] ~(hm-fn code-gen msg-form k))])))]
    (vector
      `(defprotocol ~P
         ~(str "Protocol for " (algorithm-string code-gen) " hash generation.")
         ~@(when (support-hash? code-gen)
             [`(~hash-f [this#])])
         ~@(when (support-hmac? code-gen)
             [`(~hmac-f [this# key#])]))
      `(extend-protocol ~P
         (class (byte-array 0))
         ~@(gen-impl bytes->hash bytes->hmac sym)
         String
         ~@(gen-impl bytes->hash bytes->hmac `(.getBytes ~sym))
         InputStream
         ~@(gen-impl stream->hash stream->hmac sym)
         File
         ~@(when (support-hash? code-gen)
             [`(~hash-f [this#]
                  (with-open [~sym (FileInputStream. this#)]
                    ~(stream->hash code-gen sym)))])
         ~@(when (support-hmac? code-gen)
             [`(~hmac-f [this# ~k]
                  (with-open [~sym (FileInputStream. this#)]
                    ~(stream->hmac code-gen sym k)))])
         nil
         ~@(when (support-hash? code-gen) [`(~hash-f [this#] nil)])
         ~@(when (support-hmac? code-gen) [`(~hmac-f [this# k#] nil)])))))

(defn- generate-compute-fns
  "Generate functions that call a prviously created hash function and wrap the result
   in string/byte conversions. The created functions, based on an id X, will be:

   - `X` : outputs hex string
   - `X-bytes` : outputs byte array
   - `X-file` : input is path to file, outputs string
   - `X-file-bytes` : input is path to file, outputs byte array
   - `X*` : outputs the actual hash value generated by the given function
   - `X-file*` : input is path to file, outputs the actual hash value generated by the given function
   - `X-hmac` : outputs HMAC string
   - `X-hmac-bytes` : outputs HMAC byte array
   - `X-hmac-file` : input is path to file, outputs HMAC string
   - `X-hmac-file-bytes` : input is path to file, outputs HMAC byte array
   - `X-hmac*` : outputs the actual hash value generated by the given HMAC function
   - `X-hmac-file*` : input is path to file, outputs the actual hash value generated by the given HMAC function

  The HMAC functions take two parameters: the input entity and a key that implements `ByteArrayConvertable`.
  "
  [code-gen id hash-f hmac-f]
  (let [id-bytes (symbol (str id "-bytes"))
        id-file (symbol (str id "-file"))
        id-file-bytes (symbol (str id "-file-bytes"))
        id-raw (symbol (str id "*"))
        id-raw-file (symbol (str id "-file*"))
        id-hmac (symbol (str id "-hmac"))
        id-hmac-bytes (symbol (str id "-hmac-bytes"))
        id-hmac-file (symbol (str id "-hmac-file"))
        id-hmac-file-bytes (symbol (str id "-hmac-file-bytes"))
        id-hmac-raw (symbol (str id "-hmac*"))
        id-hmac-raw-file (symbol (str id "-hmac-file*"))
        sym (gensym "v")
        k (gensym "k")
        fsym (vary-meta sym assoc :tag `String)
        call-hmac-direct `(~hmac-f ~sym (convert-to-byte-array ~k))
        call-hmac-file `(with-open [in# (FileInputStream. ~fsym)] (~hmac-f in# (convert-to-byte-array ~k)))
        call-hash-direct `(~hash-f ~sym)
        call-hash-file `(with-open [in# (FileInputStream. ~fsym)] (~hash-f in#))]
    (concat
      (when (support-hmac? code-gen)
        (vector
          `(defn ~id-hmac-raw [~sym ~k] ~call-hmac-direct)
          `(defn ~id-hmac-raw-file [~fsym ~k] ~call-hmac-file)
          `(defn ~id-hmac-bytes [~sym ~k] ~(hmac->bytes code-gen call-hmac-direct))
          `(defn ~id-hmac-file [~sym ~k] ~(hmac->string code-gen call-hmac-file))
          `(defn ~id-hmac-file-bytes [~sym ~k] ~(hmac->bytes code-gen call-hmac-file))
          `(defn ~id-hmac [~sym ~k] ~(hmac->string code-gen call-hmac-direct))))
      (when (support-hash? code-gen)
        (vector
          `(defn ~id-raw [~sym] ~call-hash-direct)
          `(defn ~id-raw-file [~fsym] ~call-hash-file)
          `(defn ~id-bytes [~sym] ~(hash->bytes code-gen call-hash-direct))
          `(defn ~id-file [~fsym] ~(hash->string code-gen call-hash-file))
          `(defn ~id-file-bytes [~fsym] ~(hash->bytes code-gen call-hash-file))
          `(defn ~id [~sym] ~(hash->string code-gen call-hash-direct)))))))

(defn generate-hash
  "Generate protocol and computing functions using the give code generator and
   function id."
  [code-gen id]
  (let [sym (gensym "this")
        hash-f (gensym (str "compute-" id "-hash"))
        hmac-f (gensym (str "compute-" id "-hmac"))]
    `(do
       ~@(generate-protocol-impl code-gen id hash-f hmac-f)
       ~@(generate-compute-fns code-gen id hash-f hmac-f))))

;; ## Dynamic Variable for Buffer Size

(def ^:dynamic *buffer-size* 2048)
