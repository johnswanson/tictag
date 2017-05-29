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
                     "TicTag will contact you. "
                     "You respond with what you're doing " [:i "right then"] "."
                     "You simply respond with tags (like \"work\" or \"coding\" or \"family\" or \"eat\" or \"cook\" or \"run\" or \"read\" or...)."]
                    [re-com/p "This random sampling of your time provides you with a statistically accurate picture of where your time "
                     [:i "actually goes"] "."]
                    [re-com/p
                     "So how much time do I spend coding? TicTag knows!"]
                    [:a {:href "https://www.beeminder.com/jds02006/coding"}
                     [:img {:width "650px"
                            :src "https://jds.objects-us-west-1.dream.io/screenshots/2017-05-28_16.08.54.png"}]]
                    [re-com/p
                     "How much time do I spend working on this project? TicTag knows!"]
                    [:a {:href "https://www.beeminder.com/jds02006/ttc"}
                     [:img {:width "650px"
                            :src "https://jds.objects-us-west-1.dream.io/screenshots/2017-05-28_16.09.48.png"}]]
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


