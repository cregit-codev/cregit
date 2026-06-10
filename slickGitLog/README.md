# slickGitLog

This program scans a git repository and extracts metadata of the commits into a _sqlite3_ database.

## How to run

```sh
java -jar gitLogToDB.jar <dbfile> <pathToGitrepo>
```

## Schema

Main commits table:

```sql
CREATE TABLE commits (
    cid character(40),
    autname TEXT,
    autemail TEXT,
    autdate TEXT,
    comname TEXT,
    comemail TEXT,
    comdate TEXT,
    summary varchar,
    ismerge boolean,
    PRIMARY KEY(cid));
```

Parents of commits:

```sql
CREATE TABLE parents (
    cid character(40),
    idx integer,
    parent character(40),

    PRIMARY KEY(cid,idx),
    FOREIGN KEY(cid) REFERENCES commits(cid),
    FOREIGN KEY(parent) REFERENCES commits(cid)
);
```

Footers (such as "_Sign-Off-By_"):

```sql
CREATE TABLE footers (
    cid character(40),
    idx integer,
    key TEXT,
    value TEXT,

    PRIMARY KEY(cid,idx),
    FOREIGN KEY(cid) REFERENCES commits(cid),
    FOREIGN KEY(parent) REFERENCES commits(cid)
);
```

Full log. it is stored in a different table due than commits:

```sql
CREATE TABLE logs (
    cid character(40),
    log TEXT,

    PRIMARY KEY(cid),
    FOREIGN KEY(cid) REFERENCES commits(cid)
);
```

## License

This software is licensed under the GPL+3.0

| | | |
|-|-|-|
| jgit        | https://eclipse.org/jgit/           | BSD3   |
| poi scala   | https://github.com/folone/poi.scala | Apache-2.0   |
| apache poi  | https://poi.apache.org/             | Apache-2.0   |
| slick       | http://slick.lightbend.com/         | MIT          |
| sqlite-jdbc |                                     | Apache-2.0   |
| HikariCP    |                                     | Apache-2.0   |

## TODO

- Many of the footers returned by `jgit` are invalid. There is need for an allowlist of key-values.
