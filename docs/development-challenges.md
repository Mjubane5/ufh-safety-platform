# Development challenges, alternatives and mitigations

A record of the problems we hit getting the prototype running, what we chose
instead when the obvious path was closed to us, what we could not solve, and
how we worked around it.

This is written for two readers: a teammate who hits the same wall next month
and wants the answer, and the examining panel, who will reasonably ask why the
system is built the way it is. Where we made a trade-off, the trade-off is
stated rather than hidden.

Scope: the authentication slice and the incident reporting slice, up to the
point where a student could sign in and file a report against a real database.

---

## 1. Environment and tooling

### 1.1 No administrator rights on the lab machines

**Problem.** The MySQL installer needs administrator rights to register a
Windows service. We do not have them on university machines, so the documented
install path in `backend/README-BACKEND.md` could not be followed.

**Alternatives considered.**

| Option | Why we did not take it |
| --- | --- |
| Ask IT for admin rights | Turnaround is days; we needed to test the same afternoon |
| Cloud MySQL (free tier) | Puts synthetic student data on a third-party host, and needs a card |
| H2 in-memory database | Different SQL dialect. We would be testing against a database we do not ship, and MySQL-specific behaviour such as how a unique index treats repeated `NULL`s would go untested |
| SQLite | Same objection, and no Spring Boot starter we already depend on |

**What we did.** Ran MySQL 8.0.41 from the portable ZIP distribution, which
needs no installer and no service registration:

```
mysqld --initialize-insecure --datadir=<path>
mysqld --datadir=<path> --port=3399
```

**Limitation.** Port 3399 rather than the default 3306, because a machine may
already have something on 3306. Every developer therefore has to override
`DB_URL`, which is why that value is an environment variable in
`application.properties` and not a literal.

**Note for whoever repeats this.** Guessing `cdn.mysql.com` download URLs
returns 404. Use the `dev.mysql.com/get/Downloads/...` form.

### 1.2 Killing the backend left the port occupied

**Problem.** Stopping the Maven wrapper did not stop the application. `mvnw
spring-boot:run` forks a separate JVM, and killing the wrapper orphans that
JVM, which keeps holding port 8080. The next start then failed with "port
already in use" and it was not obvious why.

**Mitigation.** Find the process actually holding the port and kill that,
rather than the thing you started:

```
netstat -ano | findstr :8080
taskkill /PID <pid> /F
```

**Discipline that came out of this.** Confirm what a process actually is
before killing it. At one point port 5500 was being served by a team member's
own Python server, and killing "whatever is on 5500" would have interrupted
their work. `Get-CimInstance Win32_Process` shows the full command line.

### 1.3 Windows treats filenames as case-insensitive; Git does not

**Problem.** `git add CLAUDE.md` reported success and staged nothing. The file
is tracked as `Claude.md`. Windows resolves both spellings to the same file, so
the editor opened it happily, but Git matched the pathspec literally against
the index and found no such entry.

**Mitigation.** Use `git ls-files` to get the exact tracked spelling before
scripting anything against a path. This class of bug is silent, which is what
makes it expensive.

### 1.4 Git Bash rewrites arguments that look like paths

**Problem.** Commands containing `repos/owner/name` or a `ref:path` argument
failed with errors naming `C:/Program Files/Git/...`. The MSYS layer rewrites
anything that looks like a Unix path into a Windows one, including arguments
that were never paths.

**Mitigation.** `export MSYS_NO_PATHCONV=1`, or drop the leading slash.

### 1.5 Log output disappeared when piped

**Problem.** `mvnw spring-boot:run | tail -40` printed nothing. `tail` buffers
until the input stream closes, and a server does not exit, so nothing was ever
flushed.

**Mitigation.** Do not pipe a long-running process. Write to a file and read
the file separately.

**Related.** A readiness loop with no sleep in it completed ninety iterations
in about two seconds and declared the server down. Poll with an actual delay.

---

## 2. Database and persistence

### 2.1 Staff accounts have no student number

**Problem.** `users.student_number` was `NOT NULL`. Campus control officers,
responders and GBV officers are not students and have no student number. The
seed data could not be inserted.

**Alternatives considered.**

| Option | Why we rejected it |
| --- | --- |
| Store `""` for staff | `docs/api-contract.md` says empty values are `null`, never `""`. It also breaks the unique index, since two staff members would collide on the same empty string |
| Store a fake number like `000000` | Invents data that looks real. Someone would eventually search for it |
| Separate `staff` table | Cleaner in the long run, but splits authentication across two tables and rewrites the whole auth slice for a prototype deadline |

**What we did.** Made the column nullable. MySQL permits many `NULL`s in a
unique index, so the uniqueness guarantee for real student numbers survives.

**Limitation we had to work around.** `spring.jpa.hibernate.ddl-auto=update`
adds columns but will **not** relax an existing `NOT NULL` constraint. Changing
the entity was therefore not enough on its own, and the failure appears at
insert time rather than at start-up, which makes it look like a data bug rather
than a schema one.

**Mitigation.** `database/02-alter-users-student-number-nullable.sql`, to be
run once against an existing database. Anyone creating a database from scratch
does not need it. This is the step most likely to be skipped, so it is called
out in the pull request and here.

### 2.2 Enum constants versus contract wire values

**Problem.** The contract fixes incident status strings as `reported`,
`triaged`, `assigned`, `en_route`, `on_scene`, `resolved`, `cancelled`. The
natural Java approach, `@Enumerated(EnumType.STRING)`, stores the *constant
name*, so `EN_ROUTE` would have gone into the database and `"EN_ROUTE"` onto
the wire. The frontend compares against `'en_route'` and would have silently
matched nothing.

**Alternatives considered.**

| Option | Why we rejected it |
| --- | --- |
| Rename constants to lowercase (`en_route`) | Violates Java naming convention, and the panel would rightly ask about it |
| `@Enumerated(ORDINAL)` | Stores 0, 1, 2. Inserting a new status in the middle silently reinterprets every existing row |
| Convert in the controller | Scatters the mapping across every endpoint that touches a status |

**What we did.** Two mechanisms, because there are two boundaries:

- **Database boundary:** a JPA `AttributeConverter` with `autoApply = true`, so
  every entity field of that type is converted without annotating each one.
- **JSON boundary:** Jackson `@JsonValue` on the accessor and `@JsonCreator` on
  the factory method, so serialisation and deserialisation both use the wire
  value.

`IncidentEnumTest` pins all seven status strings and all nine type strings to
the contract, so a future rename fails the build instead of failing in the
browser.

**Verification.** Confirmed in the live database that `incidents.status` is a
`varchar(20)` holding `en_route`, not an integer and not `EN_ROUTE`.

### 2.3 A derived field that only existed at write time

**Problem.** `locationSource` is derived, not supplied by the client: `"device"`
when coordinates arrived, `"none"` when they did not. It was computed in a
`@PrePersist` callback. That works when a row is written, but the service reads
the value straight off the entity to build its `201` response, and at that
point the callback had not run, so the API returned `null` for a field the
contract says is always present.

**How we found it.** The unit tests failed. They mock the repository, so no JPA
lifecycle callback fires, which is exactly the condition that exposed the bug.
Had we only tested through a live database it would have passed locally and
failed for whoever integrated against it later.

**Mitigation.** Derive the value in the `setLatitude` and `setLongitude`
setters so it is always consistent with the coordinates, and keep the
`@PrePersist` call as a backstop for entities built another way.

**Worth stating plainly:** the test caught a real defect. It was not a broken
test that needed adjusting to pass.

---

## 3. API behaviour

### 3.1 Every unhandled error was a 500

**Problem.** `GlobalExceptionHandler` had a catch-all on `Exception.class`. A
request to a URL with no handler, and a request body Jackson could not parse,
both fell into it and returned `500 SERVER_ERROR`.

**Why that mattered more than it looks.** A 500 tells the frontend team the
backend crashed. They go hunting for a backend bug when in fact they typed the
wrong path, or the endpoint simply is not built yet. It cost real time before
we fixed it.

**What we did.** Two handlers placed above the catch-all. Spring picks the most
specific match, so ordering in the file is not what decides it, but keeping
them above keeps the intent readable.

- `NoResourceFoundException` → `404 NOT_FOUND`
- `HttpMessageNotReadableException` → `400 VALIDATION_FAILED`

The second also covers a value outside an enum, such as `"en-route"` instead of
`"en_route"`.

**Limitation accepted deliberately.** The 500 handler logs the stack trace but
does not put the exception message in the response. An exception message can
name internal classes and database columns. A test asserts that `password_hash`
never appears in a response body.

### 3.2 Optional coordinates, but not half of them

**Problem.** The contract requires that a student with location blocked, or no
GPS fix indoors, can still file a report. So coordinates are optional. But a
latitude with no longitude is not a partial location, it is a wrong one: it
would plot the report on the prime meridian.

**What we did.** Missing coordinates are accepted and produce
`locationSource: "none"`. A lone latitude or a lone longitude is a `400`, with
`field` naming the one that is missing.

**Trade-off, stated because the panel will ask.** We prefer availability over
data completeness here. An incomplete report that reaches campus control is
worth more than a complete one that was never sent. The cost is that some
incidents need manual triage.

### 3.3 Stopping one student reading another student's incidents

**Problem.** `GET /api/incidents` serves four roles from one endpoint. A
student may see only their own reports, a responder only what is assigned to
them, campus control and admin see everything.

**Alternative rejected.** Fetch a page and filter it in Java. This is wrong in
two ways: the caller can page through the underlying data, and `totalItems`
would count rows the caller is not allowed to see, leaking how many exist.

**What we did.** The role decides which repository query runs, so the
restriction is a `WHERE` clause the database applies before paging. Tests
assert that a student's request calls the reporter-scoped query and never
`findAll`.

**Verified with real data.** From one database: student sees 3, campus control
sees 4, responder sees 0.

### 3.4 Paging starts at 1 in the contract and 0 in Spring Data

**Problem.** `docs/api-contract.md` numbers pages from 1. Spring Data's
`PageRequest` numbers from 0. Getting this wrong silently returns page 2 when
the client asked for page 1, and nobody notices until a record goes missing.

**Mitigation.** Convert in one place, in the service, and cover it with a test
that asserts page 1 produces `PageRequest` page 0 while the response still
reports `page: 1`.

---

## 4. Frontend and integration

### 4.1 Opening a page from disk breaks every request

**Problem.** Double-clicking `login.html` loads it over `file://`. The browser
then sends `Origin: null`, which is not in the CORS allow-list, so every
request fails with a message the browser deliberately does not explain.

**Mitigation.** Always serve the frontend over HTTP:

```
python -m http.server 5500
```

The backend allow-list contains `http://localhost:5500` and
`http://127.0.0.1:5500`. Note these are different origins to a browser, so both
are listed.

### 4.2 Building pages before the backend existed

**Problem.** Four people on frontend and four on backend. The frontend could
not wait for endpoints to exist.

**What we did.** A `MOCK` flag in `frontend/js/config.js`. With `MOCK = true`,
`api.js` returns fake data shaped field-for-field like the contract responses,
and no network call happens. Flipping one line switches every page to the real
backend.

**Limitation and the rule that follows from it.** `MOCK = true` is what gets
committed. Committing `false` breaks every teammate who does not have a backend
running. Flipping it is a local change for testing, never a change the rest of
the team inherits.

**Second flag, `MOCK_ENFORCE_AUTH`.** Mock mode can pretend to reject calls
without a token. Set it to `true` once login works, so a page that forgot to
check the user is signed in is caught in mock mode rather than after go-live.

---

## 5. Process and collaboration

### 5.1 Merge order mattered and was not obvious

**Problem.** Two pull requests were independently correct and broke when merged
in the wrong order. The seed data inserts staff rows with a `NULL` student
number, which only works after the column has been made nullable.

**Mitigation.** State the dependency in the pull request description rather
than assuming the reviewer will infer it. The required order was: nullable
column, then seed data, then incident entity, then incident API.

### 5.2 A pull request merged into a branch that had already merged

**Problem.** The incident API pull request was based on the incident entity
branch rather than on `main`, because it needed that entity to compile. The
entity branch was merged into `main`, and twenty-six seconds later the API
branch was merged into the *entity branch* — which by then no longer fed
anything. The API code was therefore stranded on a dead branch and never
reached `main`, while the pull request showed a green "Merged" badge.

**Why it was hard to see.** GitHub reports the merge as successful, because it
was. It merged into exactly the branch it was told to.

**Mitigation.** When a pull request is stacked on another, check the base
branch reads `main` before merging it. GitHub retargets automatically when the
base branch is deleted on merge, but not if the base branch is kept.

**Recovery.** Opened a fresh pull request from the same branch against `main`.
No code changed.

**Lesson.** A "Merged" badge is not evidence that code is on `main`. Verify:

```
git fetch origin
git merge-base --is-ancestor <commit> origin/main && echo on main
```

### 5.3 Access token could push but could not comment

**Problem.** Code review comments failed with `403 Resource not accessible by
personal access token (addComment)`, while pushing to the same repository
worked.

**Diagnosis.** The REST response carries an `X-Accepted-Github-Permissions`
header naming exactly what the endpoint wants. It asked for
`issues=write; pull_requests=write`.

**The non-obvious part.** A conversation comment on a pull request is an
*issue* comment in GitHub's data model. Granting only `Pull requests: write` is
not enough; `Issues: write` is the permission usually missed.

**Resolution.** Re-authenticated with a browser OAuth flow, which issues a
token with the `repo` scope, instead of continuing to adjust a fine-grained
token's permission matrix.

### 5.4 Branch protection requires a review that you cannot give yourself

**Problem.** `main` carries a ruleset requiring one approving review. GitHub
does not let you approve your own pull request, so work stalls if the author is
also the only person merging.

**Mitigation.** Pair up for review. Nothing reaches `main` without a second
person having read it, which is the point of the rule.

---

## 6. Known limitations of the prototype

Stated plainly, because a limitation we have named is a limitation we
understand.

1. **Not connected to any live emergency service or GBV support unit.** No
   report leaves this system. All data is synthetic.
2. **No transport security.** Everything runs over HTTP on localhost. A real
   deployment needs TLS before any token crosses a network.
3. **`ddl-auto=update` is a development convenience, not a migration tool.** It
   adds columns and will not remove or narrow one. A production system needs
   proper versioned migrations.
4. **The C++ priority module is not in the request path.** Priority is computed
   by an equivalent Java component. The contract explicitly allows this.
5. **No rate limiting.** Nothing stops repeated login attempts or a flood of
   reports.
6. **Endpoints still to build.** Single-incident retrieval, status transitions
   and responder assignment.
7. **Tests do not cover the database layer.** Service logic is unit tested
   against a mocked repository, and the endpoints were verified by hand against
   a live MySQL. There is no automated integration test, so a bad query would
   pass the suite.

---

## 7. What we would do differently

- **Write the enum tests first.** The wire-value mismatch would have been
  caught before any entity existed.
- **Branch from `main` wherever possible.** Every stacked pull request cost us
  something, and one of them cost us a merge that silently did nothing.
- **Verify, do not assume.** More than once a confident guess about why
  something was broken turned out to be wrong, and the check was cheaper than
  the guess. Compile it, query it, curl it.
