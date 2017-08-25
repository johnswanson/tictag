(ns tictag.views.about
  (:require [re-com.core :as re-com]
            [tictag.nav :refer [route-for]]))

(def tagtime-link [re-com/hyperlink-href :label "TagTime" :href "http://messymatters.com/tagtime/"])

(def about-content [[re-com/title :level :level1 :label "About TicTag"]
                    [re-com/p
                     "TicTag is stochastic time tracking, heavily inspired/stolen from "
                     tagtime-link
                     "."]
                    [re-com/title :level :level2 :label "Okay, what's that?"]
                    [re-com/p
                     "(On average) every 45 minutes, on an unpredictable schedule,"
                     [re-com/info-button :info (str "Technically the time between each ping is drawn from an exponential distribution. "
                                                    "This means that you never know (or have any knowledge about) when the next ping might be. "
                                                    "Although on average they "
                                                    "come 45 minutes apart, in reality you might have two pings within 5 minutes of each other, "
                                                    "then no ping for hours. Some examples: the odds of a ping occurring in any given hour is 73.64%. The odds "
                                                    "of a ping occurring in any given 5 minute period is 10.51%.")]
                     "TicTag will ping you (currently via slack, though someday I'll get around to implementing real clients). "

                     "You respond with what you're doing " [:i "right then"] "."
                     "You simply respond with tags (like \"work\" or \"coding\" or \"family\" or \"eat\" or \"cook\" or \"run\" or \"read\" or...)."]
                    [re-com/p "This random sampling of your time provides you with a statistically accurate picture of where your time "
                     [:i "actually goes"] "."]
                    [re-com/p
                     "So how much time do I spend coding? TicTag knows! (Click on the graph to see a larger version)"]
                    (let [coding-link "https://agh.io/api/graph?sig=eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJxdWVyeSI6IihvciBkZXYgY29kaW5nKSIsInVzZXItaWQiOjF9.MGUCMQDGJJPTKcZVZAWGkAt5Nf_g5GdTg5O2jRVA4jCdIFbPAT5Soouq84vMh0E9_CGevrICMFqtrArVGuEvxNEWblhy2nxJzYOSLZUmNITTOVqPMHil8SKDNVnEHZHwZkTPLA90ow"]
                      [:a {:href   coding-link
                           :target :_blank}
                       [:img {:width "850px"
                              :title "Time spent coding"
                              :src   coding-link}]])
                    [re-com/p
                     "How much time do I spend working on this project? TicTag knows!"]
                    (let [ttc-link "https://agh.io/api/graph?sig=eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJxdWVyeSI6InR0YyIsInVzZXItaWQiOjF9.MGQCMG9rkoU6_pBojnXLiHnl6Lr5wna2DZbMWI29Sw65SzCV95irHW7Jb4fu7jSNbo1utgIwS886Iyr_y9JGU1dG3seQ0J-6uZl0NNXgEin08YfeORLoVHAgUhsWgUevG6xwY4Fe"]
                      [:a {:href   ttc-link
                           :target :_blank}
                       [:img {:width "850px"
                              :title "Time spent working on TTC"
                              :src   ttc-link}]])
                    [re-com/p
                     "How about reading?"]
                    (let [read-link "https://agh.io/api/graph?sig=eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJxdWVyeSI6InJlYWQiLCJ1c2VyLWlkIjoxfQ.MGUCMHd1XcbvhSfx0jysiNVK73DjBHZts3IcqB5RVqd9MhaZIgtiJwsV4YXoXhLkGVPVywIxAKVL9eqyKuYIl3MQR2etSNqpd2-hG7u-Hh0fHyt8gQWrD_g2vtM-N_XQl5x03ftiRg"]
                      [:a {:href   read-link
                           :target :_blank}
                       [:img {:width "850px"
                              :title "Time spent reading"
                              :src   read-link}]])
                    [re-com/p
                     "Cooking or cleaning?"]
                    (let [cook-link "https://agh.io/api/graph?sig=eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJxdWVyeSI6IihvciBjb29rIGNsZWFuKSIsInVzZXItaWQiOjF9.MGQCMFDCcyZyQ2XUQoEFarSU_r-w-zBuRGmWxZs7Lae8ZkPCR4y7CWoEK-THB78LHiuwRAIwOsymQQGchg6wuSMGsonPwQHWAq8yN7VJ9gSagRfacc8k_HHGjenR1PuwkhsXjx_k"]
                      [:a {:href   cook-link
                           :target :_blank}
                       [:img {:width "850px"
                              :title "Time spent cooking or cleaning"
                              :src   cook-link}]])
                    [re-com/p "TicTag also knows that on an average day, I spend:"]
                    [:ul
                     [:li "7h30m sleeping"]
                     [:li "1h18m cooking"]
                     [:li "1h7m eating"]
                     [:li "39m cleaning up"]
                     [:li "and 26 minutes in the car"]]
                    [re-com/title :level :level2 :label "Why it's awesome"]
                    [re-com/p "Okay, there are a lot of time tracking tools out there. Why is this crazy-seeming method better? The " tagtime-link " "
                     "article probably explains it better than I will, but: TicTag/TagTime gets you the laziness of methods like RescueTime, with the accuracy "
                     "of methods like manual time tracking. You can zone out and forget all about time tracking (because it will call you!) but you also "
                     "can correctly classify time that RescueTime would get wrong: things like 'talking to a coworker while Reddit is open in my browser'"
                     "or 'drooling on my keyboard with vim open'."]
                    [re-com/p "Because TicTag works on multiple clients and is tracking what " [:span.italic "you" ] " are doing (not your device), you can also "
                     "track time that RescueTime and the like would miss entirely. I "
                     "probably spend too much of my time at the computer, but I don't spend " [:span.italic "all"] " of it here. I want to track time "
                     "spent with kids, biking, cooking, cleaning--everything! Just tracking the time I spend on the computer isn't nearly as useful to me."]
                    [re-com/p ""]
                    [re-com/title :level :level2 :label "Beeminder Integration"]
                    [re-com/p
                     "In addition to tracking your time, you might want to change how you're spending it. "
                     [re-com/hyperlink-href :label "Beeminder" :href "https://www.beeminder.com"] " lets you do that. You commit (with actual money!) to spending [x] "
                     "hours on [y]. TicTag measures how you're spending your time, and sends it along to Beeminder. If you aren't doing what you said you would, Beeminder "
                     "will yell at/charge you."]
                    [re-com/title :level :level2 :label "Warning: Alpha status!"]
                    [re-com/p
                     "Currently TicTag is in alpha. Things might change without warning. Things might break utterly. TicTag might accidentally tag all your time as 'pooping'"
                     " and send it to your boss."]
                    [re-com/p
                     "More seriously though--TicTag isn't done yet. I have a " [re-com/hyperlink-href :label "trello board" :href "https://trello.com/b/oBVNtpfr"] " where "
                     "you can see a tiny slice of the stuff that remains to be done. "
                     "Major goals:"
                     [:ul
                      [:li "Improve the dashboard (more complex queries, better graphs)"]
                      [:li "Desktop clients syncing with TicTag (but working offline)"]
                      [:li "Fix the million bugs"]]]
                    [re-com/p
                     "If you're interested in trying out TicTag, go ahead and " [re-com/hyperlink-href :label "create an account" :href (route-for :signup)] " now!"]])

(defn about []
  [re-com/v-box
   :align :center
   :children about-content])


