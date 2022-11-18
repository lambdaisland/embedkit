# lambdaisland/embedkit

<!-- badges -->
<!-- [![CircleCI](https://circleci.com/gh/lambdaisland/embedkit.svg?style=svg)](https://circleci.com/gh/lambdaisland/embedkit) [![cljdoc badge](https://cljdoc.org/badge/lambdaisland/embedkit)](https://cljdoc.org/d/lambdaisland/embedkit) [![Clojars Project](https://img.shields.io/clojars/v/lambdaisland/embedkit.svg)](https://clojars.org/lambdaisland/embedkit) -->
<!-- /badges -->

Use Metabase as a dashboard engine

<!-- opencollective -->

&nbsp;

<img align="left" src="https://github.com/lambdaisland/open-source/raw/master/artwork/lighthouse_readme.png">

&nbsp;

## Support Lambda Island Open Source

embedkit is part of a growing collection of quality Clojure libraries and
tools released on the Lambda Island label. If you are using this project
commercially then you are expected to pay it forward by
[becoming a backer on Open Collective](http://opencollective.com/lambda-island#section-contribute),
so that we may continue to enjoy a thriving Clojure ecosystem.

&nbsp;

&nbsp;

<!-- /opencollective -->

## Sponsors

Initial development of EmbedKit is generously sponsored by [Eleven](https://runeleven.com).

## Features

<!-- installation -->
## Installation
deps.edn

```
lambdaisland/embedkit {:mvn/version "0.0.0"}
```

project.clj

```
[lambdaisland/embedkit "0.0.0"]
```
<!-- /installation -->

## Rationale

Metabase is able to talk to many different data sources, and to turn what it
finds into attractive dashboards, with many options for how to visualize the
results. These dashboards can then be embedded as iframes.

This library allows you to use Metabase as a dashboard engine for your
application, creating embeddable dashboards on the fly based on a pure data
(EDN) specification of the dashboard, and the cards thereon.

It takes care of all the low level plumbing, as well as many inconsistencies in
Metabase's API, and provides higher-level operations for creating multiple
related entities in one go.

It uses content-addressed caching to reuse previously created cards and
dashboards. The assumption when using this library is that these entities are
immutable, if you need a different one, just create a different one.

## Usage

Let's start with a teaser 

``` clojure
(def conn (e/connect {:user "admin@example.com" :password "..." :secret-key "..."}))

(def db (e/find-database conn "orders"))

(def dashboard (->> (e/dashboard {:name "My sales dashboard"
                                  :cards [{:card (-> (e/native-card {:name "Monthly revenue"
                                                                     :database db
                                                                     :sql {:select ["month" "SUM(amount) AS total"]
                                                                           :from ["orders"]
                                                                           :group-by ["month"]
                                                                           :order-by ["month"]}})
                                                     (e/bar-chart {:x-axis ["month"]
                                                                   :y-axis ["total"]}))
                                           :width 12 :height 10}]})
                    (e/find-or-create! conn)))

;; Open the dashboard in the browser, REPL helper for local testing
(r/browse! dashboard)

;; Get an embed-url that you can use in an iframe
(e/embed-url conn dashboard)
```

Let's pick that apart, first you need to create a connection:

``` clojure
(def conn (e/connect {:user "admin@example.com"
                      :password "..."
                      ;; See the metabase embed settings for this
                      :secret-key "..."
                      :host "localhost"
                      :port 3000
                      :https? false?}))
```

This does the initial HTTP call to Metabase to request an authorization token.
The result is a record that encapsulates everything we need to know to talk to
the API. There are a few more options related to the underlying HTTP client.
This also wraps an atom which serves as a cache.

After connecting you are expected to also call `populate-cache`. This will allow
EmbedKit to reuse cards and dashboards based on their content hash.

``` clojure
(e/populate-cache conn)
```

Next you can find the database you want to create dashboards for.

``` clojure
(def db (e/find-database "orders"))
```

This is just a little helper to find a database in Metabase by name. We
generally go to great lengths to prevent having to deal with Metabase's
incremental ids outside of Metabase. This needs to fetch the full list of
databases, but these are then cached in memory.

EmbedKit is heavily data-driven. You first create an EDN representation of the
entity you want to create. For "questions" (what Metabase internally calls
Cards) you start with the `native-card` function. Currently only native (SQL)
queries are supported.

```clojure
(e/native-card {:name "Monthly revenue"
                :database db
                :sql {:select ["month" "SUM(amount) AS total"]
                      :from ["orders"]
                      :group-by ["month"]
                      :order-by ["month"]}})
```

You can pass a `:database` entity or a `:database-id` numeric id, if you have
it. `:sql` can be a string or a map, if it's a map we run it through HoneySQL.

This returns an "entity map", which looks like this:

``` clojure
{:lambdaisland.embedkit/type :card
 :name "Monthly revenue"
 :database_id {:id 2}
 :query_type "native"
 :dataset_query {:database {:id 2}
                 :type "native"
                 :native
                 {:query "SELECT month SUM(amount) AS total FROM orders GROUP BY month ORDER BY month"}}
 :display "table"
 :visualization_settings {}
 :lambdaisland.embedkit/variables {}}
```

The keys that are namespaced (`:lambdaisland.embedkit/type` and
`:lambdaisland.embedkit/variables`) are for EmbedKit's own use, to figure out
the correct API endpoint for a given resource, and to correctly wire up multiple
entities (think: dashboard -> dashboard-card -> card), everything else is in the
format that the Metabase API expects.

Once you have an entity map like this you can run it through `find-or-create!`.

``` clojure
(e/find-or-create! conn (my-card))
```

This will check the local cache, using a hash of the data. If it didn't find a
match, then a new entity gets created. Either way what you get back is the
representation of this entity as returned by the Metabase API, augmented with
our embedkit-specific keys.

There are also functions which adjust the entity description, for instance
`bar-chart`, which changes how the result is rendered.

``` clojure
(-> (e/native-card {...})
    (e/bar-chart {:x-axis ["..."] :y-axis ["..."]})
```

Using these you can build up your own functions, describing the cards you are
want to display. Finally you get to put them together in a dashboard.

``` clojure
(e/find-or-create!
 conn
 (e/dashboard {:name "My sales dashboard"
               :cards [{:card (my-card-fn)
                        :x 5 :y 0
                        :width 12 :height 10}]}))
```

This will create the dashboards, the cards, and then the dashboard cards.

Finally you can pass the result of `find-or-create!` to `embed-url` to get a URL
you can use to create an iframe. The result is a `lambdaisland.uri`, call `str`
on it to get the URL as a string.

### Variables

Metabase allows you to create "variables" for queries/cards, hook these up to
"parameters" of dashboards, and fill them in when creating embed-urls. This
requires definitions in three different places. This is one of the things that
is extremely opaque to do via the API. We simplify this by taking variable
definitions on the cards, and wiring these up automatically to dashboard-cards,
and exposing them in embed urls via the signed payload ("locked" parameters).

The main use case so far is to allow reusing a single dashboard definition
containing some placeholder variables.

``` clojure
(e/native-card {:variables {:category {}} 
                :sql {:where [:= "category" "{{category}}"})
```

- Use `{{var_name}}` placeholders in your SQL
- Add a corresponding entry in the `:variables` map. The associated key is a map
  with variable-specific options, like `:type`. It can be left empty. The
  default `:type` is `"text"`.
  
When using this to create a dashboard, corresponding parameter will be created
for the dashboard, which will be set to "embeddable, locked". That is, you can
set them via the JWT-signed payload, but the user can't set them via the URL.

When calling `embed-url` you can pass values for these variables.

``` clojure
(e/embed-url conn (e/find-or-create! (my-dashboard)) {:variables {:category "toys"}})
```

### Other utilities

#### Initialize the metabase
See the example file from `repl_sessions/init.clj`

```
(def config {:user "admin@example.com"
             :password "xxxxxx"})
;; create admin user and enable embedded
(setup/init-metabase! config)

;; setup embedding secret key
(e/mb-put conn*
          [:setting :embedding-secret-key]
          {:form-params {:value "6fa6b6600d27ff276d3d0e961b661fb3b082f8b60781e07d11b8325a6e1025c5"}})

;; get the embedding secret key
(def config* (assoc config
                    :secret-key (get
                                 (setup/get-embedding-secret-key conn*)
                                 :value)))

;; begin normal connection
(def conn (e/connect config*))
```

#### Create a new db connection
See the example file from `repl_sessions/create_db_conn.clj`

```
;; Example for Postgres 
(def db-conn-name "metabase-db-connection-name")
(def engine "postgres")
(def details {...}) 
(setup/create-db! conn db-conn-name engine details)
```

#### Trigger the sync of a db schema and field values

```
(e/trigger-db-fn! conn "example_tenant" :sync_schema)
(e/trigger-db-fn! conn "example_tenant" :rescan_values)
```

### ID lookup utilities 
For human, it is natural to remember the name of an entity, be it a database, 
database schema, or a table. On the other hand, inside metabase, these entities are 
all represented by numeric IDs.
That is why we also provide a series of ID lookup utilities:
```
(find-database ...)  ;; get the database entity information which include db-id through database-name
(table-id ...)       ;; find out field-id by database-name, schema-name, table-name
(field-id ...)       ;; find out field-id by database-name, schema-name, table-name, field-name
(user-id ...)        ;; find out user-id by email
(group-id ...)       ;; find out group-id by group-name
```

<!-- contributing -->
## Contributing

Everyone has a right to submit patches to embedkit, and thus become a contributor.

Contributors MUST

- adhere to the [LambdaIsland Clojure Style Guide](https://nextjournal.com/lambdaisland/clojure-style-guide)
- write patches that solve a problem. Start by stating the problem, then supply a minimal solution. `*`
- agree to license their contributions as MPL 2.0.
- not break the contract with downstream consumers. `**`
- not break the tests.

Contributors SHOULD

- update the CHANGELOG and README.
- add tests for new functionality.

If you submit a pull request that adheres to these rules, then it will almost
certainly be merged immediately. However some things may require more
consideration. If you add new dependencies, or significantly increase the API
surface, then we need to decide if these changes are in line with the project's
goals. In this case you can start by [writing a pitch](https://nextjournal.com/lambdaisland/pitch-template),
and collecting feedback on it.

`*` This goes for features too, a feature needs to solve a problem. State the problem it solves, then supply a minimal solution.

`**` As long as this project has not seen a public release (i.e. is not on Clojars)
we may still consider making breaking changes, if there is consensus that the
changes are justified.
<!-- /contributing -->

<!-- license -->
## License

Copyright &copy; 2021 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
<!-- /license -->
