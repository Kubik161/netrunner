(ns web.game
  (:require [web.ws :as ws]
            [web.lobby :refer [all-games old-states] :as lobby ]
            [web.utils :refer [response]]
            [game.main :as main]
            [game.core :as core]
            [jinteki.utils :refer [side-from-str]]
            [cheshire.core :as json]
            [crypto.password.bcrypt :as bcrypt]))


(defn send-state-diffs!
  "Sends diffs generated by game.main/public-diffs to all connected clients."
  [{:keys [gameid players spectators] :as game}
   {:keys [type runner-diff corp-diff spect-diff] :as diffs}]
  (doseq [{:keys [ws-id side] :as pl} players]
    (ws/send! ws-id [:netrunner/diff
                  (json/generate-string (if (= side "Corp")
                                                 corp-diff
                                                 runner-diff))]))
  (doseq [{:keys [ws-id] :as pl} spectators]
    (ws/send! ws-id [:netrunner/diff
                  (json/generate-string  spect-diff)])))

(defn send-state!
  "Sends full states generated by game.main/public-states to all connected clients."
  ([game states]
   (send-state! :netrunner/state game states))

  ([event
    {:keys [gameid players spectators] :as game}
    {:keys [type runner-state corp-state spect-state] :as states}]
   (doseq [{:keys [ws-id side] :as pl} players]
     (ws/send! ws-id [event (json/generate-string (if (= side "Corp")
                                                    corp-state
                                                    runner-state))]))
   (doseq [{:keys [ws-id] :as pl} spectators]
     (ws/send! ws-id [event (json/generate-string spect-state)]))))

(defn swap-and-send-state! [{:keys [gameid state] :as game}]
  "Updates the old-states atom with the new game state, then sends a :netrunner/state
  message to game clients."
  (let [old-state (get @old-states gameid)]
    (swap! old-states assoc gameid @state)
    (send-state! game (main/public-states state))))

(defn swap-and-send-diffs! [{:keys [gameid state] :as game}]
  "Updates the old-states atom with the new game state, then sends a :netrunner/diff
  message to game clients."
  (let [old-state (get @old-states gameid)]
    (swap! old-states assoc gameid @state)
    (send-state-diffs! game (main/public-diffs old-state state))))

(defn handle-game-start
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id}]
  (when-let [{:keys [players gameid] :as game} (lobby/game-for-client client-id)]
    (when (lobby/first-player? client-id gameid)
      (let [strip-deck (fn [player] (update-in player [:deck] #(select-keys % [:_id])))
            game (as-> game g
                       (assoc g :started true
                                :original-players players
                                :ending-players players)
                       (assoc g :state (core/init-game g))
                       (update-in g [:players] #(mapv strip-deck %)))]
        (swap! all-games assoc gameid game)
        (swap! old-states assoc gameid @(:state game))
        (lobby/refresh-lobby :update gameid)
        (send-state! :netrunner/start game (main/public-states (:state game)))))))

(defn handle-game-leave
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id}]
  (let [{:keys [started players gameid state] :as game} (lobby/game-for-client client-id)
        old-state @state]
    (when started
      (lobby/remove-user client-id gameid)
      (main/handle-notification state (str username " has left the game."))
      (swap-and-send-diffs! game))))

(defn handle-game-concede
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id}]
  (let [{:keys [started players gameid state] :as game} (lobby/game-for-client client-id)
        side (some #(when (= client-id (:ws-id %)) (:side %)) players)]
    (when (lobby/player? client-id gameid)
      (main/handle-concede state (side-from-str side))
      (swap-and-send-diffs! game))))

(defn handle-mute-spectators
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    mute-state                          :?data}]
  (let [{:keys [gameid started state] :as game} (lobby/game-for-client client-id)
        message (if mute-state "muted" "unmuted")]
    (when (lobby/player? client-id gameid)
      (swap! all-games assoc-in [gameid :mute-spectators] mute-state)
      (main/handle-notification state (str username " " message " specatators."))
      (lobby/refresh-lobby :update gameid)
      (swap-and-send-diffs! game)
      (ws/broadcast-to! (lobby/lobby-clients gameid)
                        :games/diff
                        {:diff {:update {gameid (lobby/game-public-view (get @all-games gameid))}}})
      )))

(defn handle-game-action
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [command args] :as msg}      :?data}]

  (let [{:keys [players state gameid] :as game} (lobby/game-for-client client-id)
        old-state (get @old-states gameid)
        side (some #(when (= client-id (:ws-id %)) (:side %)) players)]
    (main/handle-action user command state (side-from-str side) args)
    (swap-and-send-diffs! game)))

(defn handle-game-watch
  "Handles a watch command when a game has started."
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid password options]}   :?data
    reply-fn                            :?reply-fn}]
  (if-let [{game-password :password state :state started :started :as game}
           (@all-games gameid)]
    (when (and user game (lobby/allowed-in-game game user))
      (if-not started
        false ; don't handle this message, let lobby/handle-game-watch.
        (if (or (empty? game-password)
                (bcrypt/check password game-password))
          (let [{:keys [spect-state]} (main/public-states state)]
            ;; Add as a spectator, inform the client that this is the active game,
            ;; add a chat message, then send full states to all players.
            ; TODO: this would be better if a full state was only sent to the new spectator, and diffs sent to the existing players.
            (lobby/spectate-game user client-id gameid)
            (ws/send! client-id [:lobby/select {:gameid gameid
                                                :started started}])
            (main/handle-notification state (str username " joined the game as a spectator."))
            (swap-and-send-state! game)
            (when reply-fn (reply-fn 200))
            true)
          (when reply-fn
            (reply-fn 403)
            false))))
    (when reply-fn
      (reply-fn 404)
      false)))

(defn handle-game-say
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    msg                                 :?data}]
  (when-let [{:keys [gameid state mute-spectators] :as game} (lobby/game-for-client client-id)]
    (if-let [{:keys [side user] :as player} (lobby/player? client-id gameid)]
      (do (main/handle-say state (jinteki.utils/side-from-str side) user  msg)
          (swap-and-send-diffs! game))
      (let [{:keys [user] :as spect} (lobby/spectator? client-id gameid)]
        (when (and spect (not mute-spectators))
          (main/handle-say state :spectator user msg)
          (swap-and-send-diffs! game))))))

(ws/register-ws-handlers!
  :netrunner/start handle-game-start
  :netrunner/action handle-game-action
  :netrunner/leave handle-game-leave
  :netrunner/concede handle-game-concede
  :netrunner/mute-spectators handle-mute-spectators
  :netrunner/say handle-game-say
  :lobby/watch handle-game-watch)