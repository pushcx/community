(ns community.components.search
  (:require [community.controller :as controller]
            [community.models :as models]
            [community.api :as api]
            [community.routes :as routes :refer [routes]]
            [community.components.shared :as shared]
            [community.util :as util :refer-macros [<? p]]
            [community.util.selection-list :as sl]
            [community.partials :as partials :refer [link-to]]
            [om.core :as om]
            [om.dom :as dom]
            [cljs.core.async :as async :refer [chan <! >! close! put! alts!]]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :refer-macros [html]]
            [clojure.string :as str])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def key->search-filter {:none :none :users :author :threads :thread :subforums :subforum})

(defn result->display-item
  "Given a suggestions result, convert to valid display item in the autocomplete menu"
  [key text {:keys [id slug] :or {id nil :slug nil}}]
  (let [search-filter (key->search-filter key)
        display-text (condp = search-filter
                      :none
                        (str "Search for " text)
                      :author
                        (str "Posts by: " text)
                      :thread
                        (str "Thread: " text)
                      :subforum
                        (str "Subforum: " text))]
      {:search-filter search-filter :text text :display display-text :id id :slug slug}))

(defn results->display-list
  "Given all results from the suggestions endpoint, return list of things to show in the autocomplete menu"
  [query-str results]
  (let [always-display (result->display-item :none query-str nil)
        display (mapcat
                  (fn [key result-set]
                    (when (not (empty? result-set))
                      (map
                        (fn [result]
                          (result->display-item key (:text result) (:payload result)))
                        result-set)))
                  (keys results) (vals results))]
    (conj display always-display)))

(def ENTER 13)
(def UP_ARROW 38)
(def DOWN_ARROW 40)
(def TAB 9)
(def ESC 27)

(defn display [show]
  (if show {} {:display "none"}))

(defn jump-to-page [{:keys [search-filter text display id slug]}]
  (routes/redirect-to 
    (condp = search-filter
      :thread (routes :thread {:id id :slug slug})
      :subforum (routes :subforum {:id id :slug slug})
      nil)))

(defn complete-suggestion [query-data suggestion]
  (if (= :none (:search-filter suggestion))
    query-data
    (-> query-data
        (assoc-in [:filters (:search-filter suggestion)] (:text suggestion))
        (assoc :text ""))))

(defn search! [query-data]
  (let [query-param-str (->> (for [[filter-name value] (:filters query-data)
                             :when value]
                         (str (name filter-name) "=" value))
                       (str/join "&"))]
    (routes/redirect-to (str (routes :search {:query (:text query-data)})
                             "?" query-param-str))))

(defn complete-and-respond! [query-data selected]
  (cond 
    (contains? #{:author :none} (:search-filter selected))
      (search! (complete-suggestion query-data selected))    
    (contains? #{:thread :subforum} (:search-filter selected))
      (jump-to-page selected)))

(defcomponent suggestions-view [app owner]
  (display-name [_] "Search suggestions")

  (render-state [_ {:keys [query-data suggestions show-suggestions?]}]
    (html
     [:ol
      {:id "suggestions" :ref "suggestions"
       :style (display (and show-suggestions? (not (empty? (:text query-data)))))}
      (for [{:keys [selected? value] :as suggestion} suggestions]
        [:li {:class (when selected? "selected")
              :onClick (fn [e]
                             (.preventDefault e)
                             (complete-and-respond! query-data value))} 
                  (:display value)])])))

(defcomponent input-view [{:keys [query-data show-suggestions! select! complete! query-text-change! complete-and-respond!]}
                          owner]
  (display-name [_] "Search Input")

  (render [_]
    (html
       [:form
        {:name "search-form"
         :onSubmit (fn [e]
                     (.preventDefault e)
                     (complete-and-respond!))}
        [:input.form-control {:ref "search-query"
                              :type "search"
                              :id "search-box"
                              :value (:text query-data)
                              :onFocus #(show-suggestions! true)
                              ;; TODO: do we need this timeout without core.async?
                              :onBlur (fn [e] (js/setTimeout #(show-suggestions! false) 100))
                              :onChange (fn [e]
                                          (query-text-change! (.. e -target -value)))
                              :onKeyDown (fn [e]
                                           (let [keycode (.-keyCode e)]
                                             (when (contains? #{UP_ARROW DOWN_ARROW TAB ENTER} keycode)
                                               (.preventDefault e)
                                               (condp = keycode
                                                 DOWN_ARROW (select! :next)
                                                 UP_ARROW (select! :prev)
                                                 TAB (complete!)
                                                 ENTER (complete-and-respond!)))))}]])))

(defn suggestion-sl [suggestions query-str]
  (->> suggestions
       (results->display-list query-str)
       sl/selection-list))

(defcomponent autocomplete [app owner]
  (display-name [_] "Autocomplete")

  (init-state [_]
    {:query-data {:text ""
                  :filters {:author nil :subforum nil :thread nil}}
     :show-suggestions? false
     :suggestions (suggestion-sl (:suggestions app) (:query-str app))})

  (will-receive-props [_ next-props]
    (when (not= (:query-str next-props) (:query-str (om/get-props owner)))
      (om/set-state! owner
                     :suggestions (suggestion-sl (:suggestions next-props) (:query-str next-props)))))

  (render-state [_ {:as state :keys [query-data suggestions]}]
    (html
     [:div {:id "search"}
      (->input-view {:query-data query-data
                     :show-suggestions! #(om/set-state! owner :show-suggestions? %)
                     :select! #(om/set-state! owner :suggestions (sl/select % suggestions))
                     :complete! (fn []
                                  (let [s (sl/selected suggestions)]
                                    (om/set-state! owner :query-data (complete-suggestion query-data s))))
                     :query-text-change! (fn [text]
                                           (om/update-state! owner :query-data #(assoc % :text text))
                                           (controller/dispatch :update-search-suggestions text))
                     :complete-and-respond! (fn []
                                              (when-let [selected (sl/selected suggestions)]
                                                (complete-and-respond! query-data selected)))})
      (->suggestions-view app {:state state})])))

(defcomponent result [{:keys [-source] :as result}]
  (display-name [_] "Result")

  (render [_]
    (html
     [:div.row.col-md-offset-1.col-md-9.search-result
      [:div.row.header
       [:div.col-md-8 (link-to (routes :thread {:id (:thread-id -source)
                                                :slug (:thread-slug -source)
                                                :post-number (:post-number -source)})
                               {:style {:color (:ui-color -source)}}
                               [:h4.thread-title (:thread -source)])]
       [:div.col-md-4 (link-to (routes :subforum {:id (:subforum-id -source)
                                                  :slug (:subforum-slug -source)})
                               {:style {:color (:ui-color -source)}}
                               [:h5 (:subforum-group -source)
                                " / "
                                (:subforum -source)])]]
      [:div.body (partials/html-from-markdown (:body -source))]
      [:div.row.footer
       [:div.col-md-10 [:a {:href (routes/hs-route
                                   :person {:hacker-school-id (:hacker-school-id -source)})}
                        (:author -source)]]
       [:div.col-md-2  (link-to (routes :thread {:id (:thread-id -source)
                                                 :slug (:thread-slug -source)
                                                 })
                                {:style {:color (:ui-color -source)}}
                                "View thread ->")]]])))

(defcomponent search-results [{:keys [search] :as app} owner]
  (display-name [_] "Search Results")

  (render [_]
    (let [results (:results search)]
      (if (empty? results)
        (html
         [:div
          "Sorry, there were no matching results for this search."])
        (html
         [:div
          [:div.col-md-offset-1 [:h4 "Search Results"]]
          [:div.results (map (partial ->result) results)]])))))
