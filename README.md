# Model Matrix

[![Build Status](https://travis-ci.org/collectivemedia/modelmatrix.svg?branch=master)](https://travis-ci.org/collectivemedia/modelmatrix)

Machine Learning Feature Engineering

* Website: https://collectivemedia.github.io/modelmatrix/
* Source: https://github.com/collectivemedia/modelmatrix/

Alternative to Spark machine learning pipeline feature extractors, focused on building sparse feature vectors.

## Where to get it

Model Matrix workflow focused around [command line interface](http://collectivemedia.github.io/modelmatrix/doc/cli.html), 
however you can use client library to apply model matrix transformations to DataFrame in your application.

To get the latest version of the model matrix, add the following to your SBT build:

``` scala
resolvers += "Collective Media Bintray" at "https://dl.bintray.com/collectivemedia/releases"
```

And use following library dependencies:

```
libraryDependencies +=  "com.collective.modelmatrix" %% "modelmatrix-client" % "0.0.1"
```

## Developing

Local PostgreSQL database required for integration tests

#### Database config

Modelmatrix can either use H2 or Postgres as databases.

modelmatrix-cli is configured to use Postgres database by default. The database configuration is located in `modelmatrix-cli/src/main/resources/reference.conf` 

    url      = "jdbc:postgresql://localhost/modelmatrix"  
    user     = "modelmatrix"  
    password = "modelmatrix"  

modelmatrix-core unit and integration tests are configured to use H2 (in memory):
  - Integration test databse configuration is located: `modelmatrix-core/src/it/resources/database_it.conf`
  - Unit test database configuration is located: `modelmatrix-core/src/test/resources/database_test.conf`
  
**N.B.: DATABASE_TO_UPPER=FALSE setting is required for H2 because of compatibility issues between Flyway and Slick**

#### Install schema

Schema migrations managed by [Flyway](http://flywaydb.org), 

If you want to add test that excepect modelmatrix matrix schema and tables to be present please implement trait `com.collective.modelmatrix.catalog.InstallSchemaBefore`


## Testing

Unit and Integration test are automatically creating/updating schema and using by default H2

    sbt test
    sbt it:test
    
If you want to test against Postgres you can overwrite the database file-based configuration by running sbt with the following runtime system properties:

    sbt -Dmodelmatrix.catalog.db.url="jdbc:postgresql://localhost/modelmatrix?user=modelmatrix&password=modelmatrix" -Dmodelmatrix.catalog.db.driver="org.postgresql.Driver" test
    sbt -Dmodelmatrix.catalog.db.url="jdbc:postgresql://localhost/modelmatrix?user=modelmatrix&password=modelmatrix" -Dmodelmatrix.catalog.db.driver="org.postgresql.Driver" it:test
    
**N.B. This will require you to have Postgres running locally with schema `modelmatrix` created and owned by user `modelmatrix`**
    
## Assembling CLI application

To run CLI you need to build application distribution first (zip or tar.gz)

    sbt universal:packageBin        
    sbt universal:packageXzTarball
    
Application will be packaged in `modelmatrix-cli/target/universal`

## Git Workflow

This repository workflow is based on [A successful Git branching model](http://nvie.com/posts/a-successful-git-branching-model/) with two main branches with an infinite lifetime:

* master
* develop

The **master** branch at origin should be familiar to every Git user. Parallel to the master branch, another branch exists called **develop**.

We consider **origin/master** to be the main branch where the source code of HEAD always reflects a production-ready state.

We consider **origin/develop** to be the main branch where the source code of HEAD always reflects a state with the latest delivered development changes for the next release. Some would call this the “integration branch”. This is where any automatic nightly builds are built from.

Further details are available in [A successful Git branching model](http://nvie.com/posts/a-successful-git-branching-model/) blog post.
