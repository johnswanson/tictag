(ns tictag.routes)

(def app-routes
  ["/" {"login"    :login
        "signup"   :signup
        "settings" :settings
        "logout"   :logout
        ""         :dashboard}])
