(ns tictag.views.about
  (:require [tictag.nav :refer [route-for]]))

(def tagtime-link [:a {:href "http://messymatters.com/tagtime/"} "TagTime"])

(def about-content
  [:div
   [:h1 "About Tictag"]
   [:p "Tictag is stochastic time tracking, heavily inspired by/stolen from " tagtime-link "."]
   [:h2 "Okay, what's that?"]
   [:p "(On average) every 45 minutes, on an unpredictable schedule, TicTag will ping you (currently via slack, or "
    "a desktop client " [:a {:href "https://github.com/johnswanson/ttc"} "available on github"] ". "
    "You respond with what you're doing " [:i "right then"] ", using tags that you choose--things like " [:code "work"]
    " or " [:code "eat"] " or " [:code "cook"] " or " [:code "read"] " or..."]
   [:p "This random sampling of your time provides you with a statistically accurate picture of where your time actually goes."]
   [:p "How much time do I spend coding? TicTag knows (you can click on the graph to see a larger version--you can see daily totals as "
    "bars at the bottom, cumulative total as a green line, the actual pings as blue dots, and two daily averages calculated in slightly different ways "
    "in red and black)."]
   (let [coding-link "https://agh.io/api/graph?sig=eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJxdWVyeSI6IihvciBkZXYgY29kaW5nKSIsInVzZXItaWQiOjF9.MGUCMQDGJJPTKcZVZAWGkAt5Nf_g5GdTg5O2jRVA4jCdIFbPAT5Soouq84vMh0E9_CGevrICMFqtrArVGuEvxNEWblhy2nxJzYOSLZUmNITTOVqPMHil8SKDNVnEHZHwZkTPLA90ow"]
     [:a {:href   coding-link
          :target :_blank}
      [:img {:width "850px"
             :title "Time spent coding"
             :src   coding-link}]])
   [:p "How much time do I spend working on this project? TicTag knows!"]
   (let [ttc-link "https://agh.io/api/graph?sig=eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJxdWVyeSI6InR0YyIsInVzZXItaWQiOjF9.MGQCMG9rkoU6_pBojnXLiHnl6Lr5wna2DZbMWI29Sw65SzCV95irHW7Jb4fu7jSNbo1utgIwS886Iyr_y9JGU1dG3seQ0J-6uZl0NNXgEin08YfeORLoVHAgUhsWgUevG6xwY4Fe"]
     [:a {:href   ttc-link
          :target :_blank}
      [:img {:width "850px"
             :title "Time spent working on TTC"
             :src   ttc-link}]])
   [:p "Reading?"]
   (let [read-link "https://agh.io/api/graph?sig=eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJxdWVyeSI6InJlYWQiLCJ1c2VyLWlkIjoxfQ.MGUCMHd1XcbvhSfx0jysiNVK73DjBHZts3IcqB5RVqd9MhaZIgtiJwsV4YXoXhLkGVPVywIxAKVL9eqyKuYIl3MQR2etSNqpd2-hG7u-Hh0fHyt8gQWrD_g2vtM-N_XQl5x03ftiRg"]
     [:a {:href   read-link
          :target :_blank}
      [:img {:width "850px"
             :title "Time spent reading"
             :src   read-link}]])
   [:p "Cooking or cleaning?"]
   (let [cook-link "https://agh.io/api/graph?sig=eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJxdWVyeSI6IihvciBjb29rIGNsZWFuKSIsInVzZXItaWQiOjF9.MGQCMFDCcyZyQ2XUQoEFarSU_r-w-zBuRGmWxZs7Lae8ZkPCR4y7CWoEK-THB78LHiuwRAIwOsymQQGchg6wuSMGsonPwQHWAq8yN7VJ9gSagRfacc8k_HHGjenR1PuwkhsXjx_k"]
     [:a {:href   cook-link
          :target :_blank}
      [:img {:width "850px"
             :title "Time spent cooking or cleaning"
             :src   cook-link}]])
   [:h2 "Why it's awesome"]
   [:p "Three reasons. First, Tictag is 100% passive. You never have to remember to check in, you never have to start or stop a task, you never have to remember how long something took. "
    "Second, Tictag is more accurate than other passive methods, that e.g. look at what program you have active to classify your time--zoning out in front of emacs should be classified as "
    "zoning out, not coding. Third, Tictag provides insights into time that other tools would miss entirely. How long do you spend driving? Rescuetime doesn't know! I love that Tictag "
    "gives me a window into things like that."]
   [:h2 "Beeminder Integration"]
   [:p "In addition to tracking your time, you might want to change how you're spending it. " [:a {:href "https://www.beeminder.com"} "Beeminder"] " lets you do that. You commit "
    "(with actual money!) to spending " [:code "x"] " hours on " [:code "y"] ". Tictag measures how you're spending your time, and sends it along to Beeminder. If you aren't doing what you "
    "said you would, Beeminder will charge you. This pulls your long-term incentives (\"I wish I read more often\") into the short-term (\"I'd rather surf Reddit than "
    "read right now... oops, except then Beeminder will charge me, so nevermind\")."]
   [:p "(I would just note that I absolutely love Beeminder, and it is the single service I've ever used that I would classify as having changed my life. I recommend it highly, "
    "even if you don't end up using Tictag.)"]
   [:h2 "Warning: Alpha Status"]
   [:p "Tictag is in alpha. Things might change without warning. Things might break entirely. Be warned."]])

(defn about []
  [:div {:style {:width "70%"
                 :margin :auto
                 :margin-top "3em"}}
   about-content])


