# tictag

[![CircleCI](https://circleci.com/gh/johnswanson/tictag.svg?style=svg)](https://circleci.com/gh/johnswanson/tictag)

Stochastic time tracking, stolen liberally from TagTime, with a centralized component to:

- merge responses from multiple clients
- send and receive slack messages requesting and receiving pings (you receive a message asking what you're doing, you respond with tags)

## Motivation

I loved using TagTime. I had two problems with it: first, while I am a nerd and spend too much time in front of a computer, I don't spend
*all* of it here! Tagging the time I spent doing things like cooking, cleaning, or spending time with family was difficult. I'd
inevitably forget to tag big swathes of time, and retrospective tagtiming never felt quite right--and big swathes of untagged time were
really demoralizing and made me less confident in my collected data.

Second, I use multiple machines--I switch from my laptop to my desktop daily, and I get more tempted by new computers than I really should.
With TagTime, that means a manual migration from the old machine to the new one. That works fine... if I remember to do it. But because
TagTime is so passive, it's pretty easy to just... not notice that I'm no longer getting pinged (especially since, hey, random
swathes of multiple hours without a ping are just part of the randomness game!)

To solve these problems, I hacked together this little project. It's kludgy at the moment and has lots of room for improvement, but it solves
my problem:

- it keeps a central TagTime (er... tictag) log on a publicly accessible machine (you could put this on AWS, GCE, Linode, etc.)
- it syncs that log with BeeMinder
- I can run it on all my machines, and all the clients will ping me and send my tags to the server
- it has a slack bot! It'll send me a message like `PING! id: 383, long-time 1490371673000`. I can respond with `code work` if I'm
responding immediately, `383 code work` for out-of-order responses, or `1490371673000` for very late responses (only the 10 most
recent `id`s work to address pings).

Downsides (current, some might be mitigated later):

- I abandoned the TagTime log format, because it didn't really support easy concurrency (I wanted clients to be able to edit a past tag without
difficulty). This means that my tools need to be more robust, since you can't edit your tags with a simple text editor. E.g. I needed a
`sleep` command to tag the most recent contiguous series of `afk` pings as `sleep`.
- Cost--you'll have to run your own server with a publicly accessible IP address. This can be like $2.50 or $5 a month. Plus time to
maintain it. (I'm hoping to add users/accounts in the near future so I can run something publicly accessible!)

## Installation and Getting Started

(This should get dramatically easier, very soon.)

1. Make sure you have clojure and leiningen installed.

2. The super complicated part, for now: create a Slack account, and a bot, and set Slack up to hit your publicly accessible URL when your bot
gets a message.

2. Clone and `cd` into this repo.

3. Check out config.clj for configuration options. Everything is configured through environment variables.

4. Either `lein run`, or `lein uberjar` and `java -jar [the resulting uberjar file]`. (You could also build a docker image, if you feel like it.)

5. *Optional, but highly recommended*: Make sure the server only serves on localhost and use something like nginx as a reverse proxy, *with SSL*.
There's currently no authentication of client requests other than a silly "shared secret" that the server/client are configured with, sent with
every request.

6. You can either use the slack bot alone, or you can run a client: https://github.com/johnswanson/tictag-client is the WIP version,
only tested on Linux with `dmenu` installed for the tag prompt...

## Beeminder Integration

Like the original TagTime, **NOTE THAT THIS WILL DELETE ALL YOUR DATA FROM YOUR BEEMINDER GOAL!** Do **not** point TicTag
at an existing beeminder goal unless you want all your data to go away.

Beeminder goal configuration is simple, the BEEMINDER_GOALS env variable should be something like `coding,work:werk` to sync
`coding` tags with a `coding` goal and `work` tags with a `werk` goal. Currently this is stupidly simple because that's my use
case, ideally in the future there will be a way to do more complex logic here (like multiple tags syncing to one goal).
