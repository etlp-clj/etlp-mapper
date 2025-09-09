# etlp-mapper

Etlp-mapper is a microservice that allows users to create jute based low code data transformation logic.
This service can be used as a standalone Jute based data transformation utility, however, this service forms a crucial component of the `etl` based smart data connectors.


## Setup


### Production Build

As a precursor you would need Leiningen, Clojure and Java installed on our machine, once we have the basic runtime up an running, we need to clone this repo and build an uberjar.


```sh
$ lein deps
$ lein uberjar

```

#### Run Migrations

This service depends on Postgres >= v14.00, after successful java jar build, we need to run the migrations to create basic set of tables for our microservice. Once the migrations are successfully applied, we can simply run our jar and it should start the web server at `localhost:3000`


```sh

$  java -jar target/etlp-mapper-0.1.0-SNAPSHOT-standalone.jar :duct/migrator

$  java -jar target/etlp-mapper-0.1.0-SNAPSHOT-standalone.jar

```

The migrations create organization-aware `mappings` and `mappings_history` tables, each keyed by an `org_id` used to scope data per tenant. Existing deployments can apply the new migrations to add these columns without dropping data.



### REPL based Interactive Development

When you first clone this repository, run:

```sh
lein duct setup
```

This will create files for local configuration, and prep your system
for the project.

### Environment

To begin developing, start with a REPL.

```sh
lein repl
```

Then load the development environment.

```clojure
user=> (dev)
:loaded
```

Run `go` to prep and initiate the system.

```clojure
dev=> (go)
:duct.server.http.jetty/starting-server {:port 3000}
:initiated
```

By default this creates a web server at <http://localhost:3031>.

When you make changes to your source files, use `reset` to reload any
modified files and reset the server.

```clojure
dev=> (reset)
:reloading (...)
:resumed
```

### Testing

Testing is fastest through the REPL, as you avoid environment startup
time.

```clojure
dev=> (test)
...
```

But you can also run tests through Leiningen.

```sh
lein test
```

### OIDC Authentication

The service secures endpoints using Keycloak OIDC. Configure the
following environment variables before starting the app:

```
OIDC_ISSUER   = http://localhost:8080/realms/mapify
OIDC_AUDIENCE = mapify-api
OIDC_JWKS_URI = http://localhost:8080/realms/mapify/protocol/openid-connect/certs
```

Run tests (if Leiningen is installed) with:

```sh
lein test
```

After acquiring an access token, you can verify authentication with:

```sh
curl -H "Authorization: Bearer $TOKEN" http://localhost:3031/whoami
```

### Bugs

## License

Copyright © 2024 Rahul Gaur

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
