# tictag

[![CircleCI](https://circleci.com/gh/johnswanson/tictag.svg?style=svg)](https://circleci.com/gh/johnswanson/tictag)

Stochastic time tracking, stolen liberally from TagTime, with a centralized component to:

- merge responses from multiple clients
- send and receive slack messages requesting and receiving pings (you receive a message asking what you're doing, you respond with tags)

You can sign up at [https://agh.io](agh.io) or run it yourself!

## Motivation

I loved using TagTime. I had two problems with it: first, while I am a nerd and spend too much time in front of a computer, I don't spend
*all* of it here! Tagging the time I spent doing things like cooking, cleaning, or spending time with family was difficult. I'd
inevitably forget to tag big swathes of time, and retrospective tagtiming never felt quite right--and big swathes of untagged time were
really demoralizing and made me less confident in my collected data.

Second, I use multiple machines--I switch from my laptop to my desktop daily, and I get more tempted by shiny new computers than I really should.
It was hard to keep my data in sync between multiple computers.

Solution: tictag! It's basically tagtime with a centralized database. It can send you pings via slack (if you want). And your responses (sent via
slack or to the API) are compiled, synced to Beeminder (just like the original TagTime).

(Oh, and also, this is also a way to experiment with web dev in clj(s))

## Installation and Getting Started

(This should get dramatically easier, very soon.)

1. Make sure you have clojure and leiningen installed.

2. The super complicated part, for now: create a Slack account, and a bot, and set Slack up to hit your publicly accessible URL when your bot
gets a message.

2. Clone and `cd` into this repo.

3. Check out config.clj for configuration options. Everything is configured through environment variables.

4. Either `lein run`, or `lein uberjar` and `java -jar [the resulting uberjar file]`.

5. You can either use the slack bot alone, or you can run a client for a much better experience: https://github.com/johnswanson/ttc (Note: this
currently has a dependency on xdialog--let me know if you want to use tictag somewhere you can't install xdialog, or on a non-Linux machine.)
