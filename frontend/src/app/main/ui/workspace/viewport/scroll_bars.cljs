;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.scroll-bars
  (:require
   [app.common.uuid :as uuid]
   [app.common.geom.shapes :as gsh]
  ;;  [app.common.geom.point :as gpt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

;; TODO: esta función es casi igual en src/app/main/ui/workspace/viewport/utils.cljs pero con rendondeo
;; (defn translate-point-to-viewport [viewport zoom pt]
;;   (let [vbox     (.. ^js viewport -viewBox -baseVal)
;;         brect    (dom/get-bounding-rect viewport)
;;         brect    (gpt/point (:left brect)
;;                             (:top brect))
;;         box      (gpt/point (.-x vbox) (.-y vbox))
;;         zoom     (gpt/point zoom)]
;;     (-> (gpt/subtract pt brect)
;;         (gpt/divide zoom)
;;         (gpt/add box))))

(defn update-vertical-scroll-position [y-delta]
  (ptk/reify ::update-vertical-scroll-position
    ptk/UpdateEvent
    (update [_ state]
                (update-in state [:workspace-local :vbox]
                       (fn [vbox]
                         (-> vbox
                             (update :y #(+ % y-delta ))))))))

(mf/defc viewport-vertical-scrollbar
  {::mf/wrap [mf/memo]}
  [{:keys [viewport-ref zoom vbox]}]

  (let [scrolling?              (mf/use-state false)
        start-ref               (mf/use-ref nil)

        base-objects            (mf/deref refs/workspace-page-objects)
        root-shapes             (get-in base-objects [uuid/zero :shapes])
        shapes                  (->> root-shapes (mapv #(get base-objects %)))
        base-objects-rect       (gsh/selection-rect shapes)

        inv-zoom                (/ 1 zoom)

        top-offset              (-> (- (:y vbox) (:y base-objects-rect)))
        bottom-offset           (-> (- (:y2 base-objects-rect) (+ (:y vbox) (:height vbox))))

        vertical-offset         (+ top-offset bottom-offset)

        top-offset              (-> top-offset
                                    (* (:height vbox))
                                    (/ (:height base-objects-rect)))

        bottom-offset           (-> bottom-offset
                                    (* (:height vbox))
                                    (/ (:height base-objects-rect)))

        show-vertical-scroll?   (or @scrolling? (> top-offset 0) (> bottom-offset 0))

        scrollbar-x             (+ (:x vbox) (:width vbox) (* inv-zoom -32) )
        scrollbar-y             (-> (+ (:y vbox) top-offset))
        scrollbar-height        (-> (- (+ (:y vbox) (:height vbox)) bottom-offset scrollbar-y))

        height-factor           (/ (+ (:height vbox) vertical-offset) (:height vbox))

        on-mouse-move
        (mf/use-callback
         (mf/deps zoom height-factor scrolling?)
         (fn [event]
           (when-let [_ @scrolling?]
             (let [start-pt    (mf/ref-val start-ref)
                   current-pt  (dom/get-client-position event)
                   delta       (/ (* height-factor (- (:y current-pt) (:y start-pt))) zoom)]
               (st/emit! (update-vertical-scroll-position delta))
               (mf/set-ref-val! start-ref current-pt)))))

        on-mouse-down
        (mf/use-callback
         (mf/deps)
         (fn [event]
           (let [start-pt (dom/get-client-position event)]
             (mf/set-ref-val! start-ref start-pt)
             (reset! scrolling? true))))

        on-mouse-up
        (mf/use-callback
         (mf/deps)
         (fn [_]
           (reset! scrolling? false)))]

    (when show-vertical-scroll?
      [:g.vertical-scroll
       [:rect {:on-mouse-move on-mouse-move
               :on-mouse-down on-mouse-down
               :on-mouse-up       on-mouse-up
               :width (* inv-zoom 7)
               :rx (* inv-zoom 3)
               :ry (* inv-zoom 3)
               :height scrollbar-height
               :fill-opacity 0.4
               :transform (str "translate(" scrollbar-x ", " scrollbar-y ")")}]])))
