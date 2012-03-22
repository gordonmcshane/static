(ns static.io
  (:use [clojure.tools logging]
        [clojure.java.shell :only [sh]]
        [cssgen]
        [hiccup core])
  (:use static.config :reload-all)
  (:import (org.pegdown PegDownProcessor)
           (java.io File)
           (java.io InputStreamReader OutputStreamWriter)
           (org.apache.commons.io FileUtils FilenameUtils)))

(def markdown-processor (PegDownProcessor.))
(defn- markdown [txt] (.markdownToHtml markdown-processor txt))

(defn- split-file [content]
  (let [idx (.indexOf content "---" 4)]
    [(.substring content 4 idx) (.substring content (+ 3 idx))]))

(defn- prepare-metadata [metadata]
  (reduce (fn [h [_ k v]]
            (let [key (keyword k)]
              (assoc h key v)))
          {} (re-seq #"([^:#\+]+): (.+)(\n|$)" metadata)))

(defn- read-markdown [file]
  (let [[metadata content]
        (split-file (slurp file :encoding (:encoding (config))))]
    [(prepare-metadata metadata) (markdown content)]))

(defn- read-html [file]
  (let [[metadata content]
        (split-file (slurp file :encoding (:encoding (config))))]
    [(prepare-metadata metadata) content]))

(def emacs nil)

(defn emacs-start []
  (let [runtime (Runtime/getRuntime)
        process (.exec runtime
                       (into-array [(:emacs (config))
                                    "-batch"
                                    "-eval"
                                    (str   
                                     "
                                       (progn
                                        (require 'cl)
                                       
                                        (defun read-expression ()
                                          (read-string \"\"))
                                       
                                        (defun read-expression-from-string (str)
                                          (read-from-string str))
                                       
                                        (defun repl ()
                                          (loop for expr = (read-string \"\") then (read-expression)
                                                do
                                                (let ((form (car (read-expression-from-string expr))))
                                                  (message \"%s\" (eval form)))))
                                       
                                        (defun revert-all-buffers ()
                                          (interactive)
                                          (dolist (buf (buffer-list))
                                                  (with-current-buffer buf
                                                    (when (and (buffer-file-name) (not (buffer-modified-p)))
                                                      (revert-buffer t t t)))))
                                       
                                        " (apply str (map second (:emacs-eval (config)))) "
                                        (repl))")]))]
    (.addShutdownHook runtime (Thread. #(.destroy process)))
    {:process process
     :input (InputStreamReader. (.getInputStream process))
     :output (OutputStreamWriter. (.getOutputStream process))
     :error (InputStreamReader. (.getErrorStream process))}))

(defn- emacs-write [stream str]
  (.write stream str)
  (.write stream (System/getProperty "line.separator"))
  (.flush stream))

(defn- emacs-read [stream]
  (while (not (.ready stream)) (Thread/sleep 10))
  (apply str
         (map char
              (loop [res []]
                (if (.ready stream)
                  (recur (concat res [(.read stream)]))
                  res)))))

(defn- read-org [file]
  (if (not (:emacs (config)))
    (do (error "Path to Emacs is required for org files.")
        (System/exit 0)))
  (if (nil? emacs)
    (alter-var-root (find-var 'static.io/emacs) (fn [c] (emacs-start))))
  (let [metadata (prepare-metadata (slurp file :encoding (:encoding (config))))
        content (do (emacs-write (:output emacs)
                                 (str
                                  "(progn "
                                  " (revert-all-buffers)"
                                  " (find-file \"" (.getAbsolutePath file) "\") "
                                  " (princ (org-no-properties (org-export-as-html nil nil nil 'string t nil))))"))
                    (emacs-read (:input emacs)))]
    [metadata content]))

(defn- read-clj [file]
  (let [[metadata content] (read-string
                              (str \( (slurp file :encoding (:encoding (config))) \)))]
    [metadata (binding [*ns* (the-ns 'static.core)] (-> content eval html))]))

(defn- read-cssgen [file]
  (let [metadata {:extension "css" :template :none}
        content (read-string
                 (slurp file :encoding (:encoding (config))))]
    [metadata (binding [*ns* (the-ns 'static.core)] (-> content eval css))]))

(defn read-doc [f]
  (let [extension (FilenameUtils/getExtension (str f))]
    (cond (or (= extension "markdown") (= extension "md"))
          (read-markdown f)
          (= extension "md") (read-markdown f)
          (= extension "org") (read-org f)
          (= extension "html") (read-html f)
          (= extension "clj") (read-clj f)
          (= extension "cssgen") (read-cssgen f)
          :default (throw (Exception. "Unknown Extension.")))))

(defn dir-path [dir]
  (cond (= dir :templates) (str (:in-dir (config)) "templates/")
        (= dir :public) (str (:in-dir (config)) "public/")
        (= dir :site) (str (:in-dir (config)) "site/")
        (= dir :posts) (str (:in-dir (config)) "posts/")
        :default (throw (Exception. "Unknown Directory."))))

(defn list-files [d]
  (let [d (File. (dir-path d))]
    (if (.isDirectory d)
      (sort
       (filter
        #(let [[metadata _] (read-doc %)
               published? (:published metadata)]
           (if (or (nil? published?)
                   (= published? "true"))
             true false))
        (FileUtils/listFiles d (into-array ["markdown"
                                            "md"
                                            "clj"
                                            "cssgen"
                                            "org"
                                            "html"]) true))) [] )))

(def read-template
     (memoize
      (fn [template]
        (-> (str (dir-path :templates) template)
            (File.)
            (#(str \( (slurp % :encoding (:encoding (config))) \) ))
            read-string))))

(defn write-out-dir [file str]
  (FileUtils/writeStringToFile
   (File. (:out-dir (config)) file) str (:encoding (config))))

(defn deploy-rsync [rsync out-dir host user deploy-dir]
  (let [cmd [rsync "-avz" "--delete" "--checksum" "-e" "ssh"
             out-dir (str user "@" host ":" deploy-dir)]]
    (info (:out (apply sh cmd)))))
