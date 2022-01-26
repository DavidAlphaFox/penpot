;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.hooks.resize
  (:require
   [app.util.dom :as dom]
   [app.common.geom.point :as gpt]
   [rumext.alpha :as mf]))

(def last-resize-type nil)

(defn use-resize-hook
  [initial min-val max-val axis negate? resize-type]
  (let [size-state (mf/use-state initial)
        parent-ref (mf/use-ref nil)

        dragging-ref (mf/use-ref false)
        start-size-ref (mf/use-ref nil)
        start-ref (mf/use-ref nil)
        
        on-pointer-down
        (fn [event]
          (dom/capture-pointer event)
          (mf/set-ref-val! start-size-ref @size-state)
          (mf/set-ref-val! dragging-ref true)
          (mf/set-ref-val! start-ref (dom/get-client-position event)))

        on-lost-pointer-capture
        (fn [event]
          (dom/release-pointer event)
          (mf/set-ref-val! start-size-ref nil)
          (mf/set-ref-val! dragging-ref false)
          (mf/set-ref-val! start-ref nil))

        on-mouse-move
        (fn [event]
          (when (mf/ref-val dragging-ref)
            (let [start (mf/ref-val start-ref)
                  pos (dom/get-client-position event)
                  delta (-> (gpt/to-vec start pos)
                            (cond-> negate? gpt/negate)
                            (get axis))
                  start-size (mf/ref-val start-size-ref)
                  new-size (-> (+ start-size delta) (max min-val) (min max-val))]
              
              (reset! size-state new-size))))]
    {:on-pointer-down on-pointer-down
     :on-lost-pointer-capture on-lost-pointer-capture
     :on-mouse-move on-mouse-move
     :parent-ref parent-ref
     :size @size-state}))

(defn use-resize-observer
  [node-ref callback]

  (let [prev-val-ref (mf/use-ref nil)
        current-observer-ref (mf/use-ref nil)

        node (mf/ref-val node-ref)
        current-observer (mf/ref-val current-observer-ref)
        prev-val (mf/ref-val prev-val-ref)]

    (when (and (not= prev-val node) (some? current-observer))
        (.disconnect current-observer))

    (when (and (not= prev-val node) (some? node))
      (mf/set-ref-val! prev-val-ref node)
      (let [observer
            (js/ResizeObserver.
             (fn [e]
               (let [size (dom/get-client-size node)]
                 (when callback (callback size)))))]
        (.observe observer node)))))
