;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.scroll-bars
  (:require
   [app.common.uuid :as uuid]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.point :as gpt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

;; TODO: esta función es casi igual en src/app/main/ui/workspace/viewport/utils.cljs pero con rendondeo
(defn translate-point-to-viewport [viewport zoom pt]
  (let [vbox     (.. ^js viewport -viewBox -baseVal)
        brect    (dom/get-bounding-rect viewport)
        brect    (gpt/point (:left brect)
                            (:top brect))
        box      (gpt/point (.-x vbox) (.-y vbox))
        zoom     (gpt/point zoom)]
    (-> (gpt/subtract pt brect)
        (gpt/divide zoom)
        (gpt/add box))))

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
        fixed-y-start?-ref      (mf/use-ref false)
        start-ref               (mf/use-ref nil)
        scrollbar-y-ref         (mf/use-ref nil)
        scrollbar-y-padding-ref (mf/use-ref nil)
        scrollbar-height-ref    (mf/use-ref nil)

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

        scrollbar-x             (+ (:x vbox) (:width vbox) (* inv-zoom -32))
        scrollbar-y             (-> (+ (:y vbox) top-offset))
        scrollbar-height        (-> (- (+ (:y vbox) (:height vbox)) bottom-offset scrollbar-y))


        fix-top (- (:y vbox) scrollbar-y)
        fix-top? (> fix-top 0)
        fix-bottom (- (+ scrollbar-y scrollbar-height) (+ (:y vbox) (:height vbox)))
        fix-bottom? (> fix-bottom 0)

        scrollbar-y (if fix-top?
                      (+ scrollbar-y fix-top)
                      scrollbar-y)

        scrollbar-y (if fix-bottom?
                      (+ scrollbar-y fix-bottom)
                      scrollbar-y)

        scrollbar-y (if (and @scrolling? (mf/ref-val fixed-y-start?-ref))
                      (mf/ref-val scrollbar-y-ref)
                      scrollbar-y)

        scrollbar-height (if fix-top?
                           (- scrollbar-height fix-top)
                           scrollbar-height)

        scrollbar-height (if fix-bottom?
                           (- scrollbar-height fix-bottom)
                           scrollbar-height)

        scrollbar-height (if (and @scrolling? (mf/ref-val fixed-y-start?-ref))
                           (mf/ref-val scrollbar-height-ref)
                           scrollbar-height)

        height-factor           (/ (+ (:height vbox) vertical-offset) (:height vbox))

        on-mouse-move
        (mf/use-callback
         (mf/deps viewport-ref zoom height-factor scrolling?)
         (fn [event]
           (when-let [_ @scrolling?]
             (let [viewport            (mf/ref-val viewport-ref)
                   start-pt            (mf/ref-val start-ref)
                   current-pt          (dom/get-client-position event)
                   delta               (/ (* height-factor (- (:y current-pt) (:y start-pt))) zoom)
                   new-scrollbar-y     (-> (translate-point-to-viewport viewport zoom current-pt)
                                           (:y)
                                           (+ (mf/ref-val scrollbar-y-padding-ref)))]
               (st/emit! (update-vertical-scroll-position delta))
               (mf/set-ref-val! scrollbar-y-ref new-scrollbar-y)
               (mf/set-ref-val! start-ref current-pt)))))

        on-mouse-down
        (mf/use-callback
         (mf/deps viewport-ref scrollbar-y scrollbar-height)
         (fn [event]
           (let [viewport            (mf/ref-val viewport-ref)
                 start-pt            (dom/get-client-position event)
                 new-scrollbar-y     (-> (translate-point-to-viewport viewport zoom start-pt)
                                         (:y))
                 scrollbar-y-padding (- scrollbar-y new-scrollbar-y)]
             (mf/set-ref-val! start-ref start-pt)
             (mf/set-ref-val! scrollbar-y-padding-ref scrollbar-y-padding)
             (mf/set-ref-val! scrollbar-y-ref (+ new-scrollbar-y scrollbar-y-padding))
             (mf/set-ref-val! scrollbar-height-ref scrollbar-height)
             (mf/set-ref-val! fixed-y-start?-ref (or fix-bottom? fix-top?))
             (reset! scrolling? true))))

        on-mouse-up
        (mf/use-callback
         (mf/deps)
         (fn [_]
           (mf/set-ref-val! fixed-y-start?-ref false)
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
