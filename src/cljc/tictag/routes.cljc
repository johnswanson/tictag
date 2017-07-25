(ns tictag.routes)

(def app-routes
  ["/" {"login"          :login
        "signup"         :signup
        "settings"       :settings
        "editor"         :editor
        "logout"         :logout
        "slack-callback" :slack-callback
        "about"          :about
        ""               :dashboard}])
