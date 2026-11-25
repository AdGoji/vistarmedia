(ns build
  (:require
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def version (str/trim (slurp "version.txt")))
(def version-tag (str "v" version))
(def lib 'com.adgoji/vistarmedia)
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def scm-url "git@github.com:adgoji/vistarmedia.git")

(defn clean [_]
  (println "Clean target dir")
  (b/delete {:path "target"}))

(defn jar [_]
  (println "Write pom")
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis
                :src-dirs  ["src"]
                :scm       {:tag                 version-tag
                            :connection          "scm:git:git://github.com/adgoji/vistarmedia"
                            :developerConnection "scm:git:ssh://git@github.com:adgoji/vistarmedia.git"
                            :url                 "https://github.com/adgoji/vistarmedia"}
                :pom-data  [[:licenses
                             [:license
                              [:name "MIT"]
                              [:url "https://opensource.org/license/mit/"]
                              [:distribution "repo"]
                              [:comments "The MIT License"]]]]})
  (println "Copy sources and resources")
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (println "Build jar")
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Created" jar-file))

(defn deploy [_]
  (println "Deploy to Clojars")
  (dd/deploy {:installer      :remote
              :artifact       (b/resolve-path jar-file)
              :pom-file       (b/pom-path {:lib       lib
                                           :class-dir class-dir})
              :sign-releases? false})
  (println "Deployed successfully"))
