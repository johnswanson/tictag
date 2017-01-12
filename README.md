# tictag

Stochastic time tracking, stolen liberally from TagTime, with a centralized component to:

- merge responses from multiple clients
- send and receive SMS messages requesting and receiving pings (you receive a text asking what you're doing, you respond with tags)

## Motivation

I loved using TagTime. I had two problems with it: first, while I am a nerd and spend too much time in front of a computer, I don't spend
*all* of it here! Tagging the time I spent doing things like cooking, cleaning, or spending time with family was difficult. I'd
inevitably forget to tag big swathes of time, and retrospective tagtiming never felt quite right--and big swathes of untagged time were
really demoralizing and made me less confident in my collected data.

Second, I use multiple machines--I switch from my laptop to my desktop daily, and I get more tempted by new computers than I really should.
With TagTime, that means a manual migration from the old machine to the new one. That works fine... if I remember to do it. But because
TagTime is so passive, it's pretty easy to just... not notice that I'm no longer getting pinged (especially since, hey, random
swathes of multuiple hours without a ping are just part of the randomness game!)

To solve these problems, I hacked together this little project. It's kludgy at the moment and has lots of room for improvement, but it solves
my problem:

- it keeps a central TagTime (er... tictag) log on a publicly accessible machine (you could put this on AWS, GCE, Linode, etc.)
- it syncs that log with BeeMinder
- I can run it on all my machines, and all the clients will ping me and send my tags to the server
- via Twilio, it sends me SMS messages at each ping-time. Each message has a short ID, which is used to respond to that particular ping.
  For example, tictag sends the message "582 PING!" and I might respond "582 run" or "582 code work" to add those tags to the tictag log.

Downsides (current, some might be mitigated later):

- I abandoned the TagTime log format, because it didn't really support easy concurrency (I wanted clients to be able to edit a past tag without
difficulty). This means that my tools need to be more robust, since you can't edit your tags with a simple text editor (I'm currently using a
SQLite database).
- Cost--you'll have to run your own server with a publicly accessible IP address, and you'll need a Twilio account, with enough credits to send
and receive your SMS messages to and from your phone.

## Installation and Getting Started

(This should get dramatically easier, very soon.)

1. There are some dependencies (some of which will be customizable later?): `java`/`clojure`/`leiningen`, `play` (and `ubuntu-sounds` from the
AUR or your distro's equivalent), and `dmenu`. Clojure/leiningen for obvious reasons (although I might release a jar file at some point to make
things easier), `play` to play sounds, [dmenu](https://wiki.archlinux.org/index.php/dmenu) as the client tag prompt. Oh, and things are only tested
with Linux at this point.

2. Set up a Twilio account.

3. Clone and `cd` into this repo, on the server and the client(s).

4. On the server: edit `~/.tictagrc`. It's a clojure file that will be `eval`ed at config-time. Look in
[tictag.config](../blob/master/src/tictag/config.clj) for the server configuration options and edit them.

5. On the server: `lein repl`, then `(reloaded.repl/set-init! server-system)`, then `(reset)` to launch the server.
(Why run the server in a Repl? This might change eventually, but being able to edit and look inside the running
program is extremely useful. For example, the `(sleep)` function can be run from the server REPL to replace the
last contiguous serious of :afk pings with :sleep pings, or the `(sleepy-pings)` function can
be used to check which pings those are, or the `(pings)` function can be used to pull all pings from the database, etc.)

6. *Optional, but highly recommended*: Make sure the server only serves on localhost and use something like nginx as a reverse proxy, *with SSL*.
There's currently no authentication of client requests other than a silly "shared secret" that the server/client are configured with, sent with
every request.

7. On the client: `lein run client https://[your-server-url]` (or `lein repl`, then `(reloaded.repl/set-init! client-system)`, then `(reset)`)

## Beeminder Integration

Like the original TagTime, **NOTE THAT THIS WILL DELETE ALL YOUR DATA FROM YOUR BEEMINDER GOAL!** Do **not** point TicTag
at an existing beeminder goal unless you want all your data to go away.
