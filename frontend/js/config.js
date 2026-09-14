// Frontend configuration.
//
// This is the only file you edit when you move between working on your own
// machine, working against a teammate's backend, or demoing the finished app.
// Nothing else in the frontend should hardcode a URL.

// Is this page being served from a developer machine, or from the hosted
// demonstration? Everything below follows from the answer, so we work it out
// once. An empty hostname means the file was opened from disk over file://.
const isLocal = ['localhost', '127.0.0.1', ''].includes(window.location.hostname);

// Where the Spring Boot backend lives. Matches docs/api-contract.md section 1.
// No trailing slash - api.js adds the path itself.
//
// Hosted, the backend serves these pages itself, so '/api' is the same origin
// and there is no cross-origin request to be blocked. On your own machine the
// pages come from port 5500 and the backend is on 8080, which is why that one
// needs the full URL.
export const BASE_URL = isLocal ? 'http://localhost:8080/api' : '/api';

// Whether this build is still a prototype: not connected to campus control,
// to the police, or to any GBV support unit, and nobody reading what comes in.
//
// Deliberately a separate constant from MOCK below, and deliberately not
// worked out from the hostname. The demonstration notice is driven by this,
// and the notice has to survive the day we point the pages at a real database
// - because that is the day the app starts *storing* reports that still reach
// nobody, which is more dangerous than mock mode, not less.
//
// Only set this to false when campus control has actually agreed to receive
// these reports and somebody is on the other end.
export const IS_PROTOTYPE = true;

// The MOCK switch.
//
//   MOCK = true   -> api.js returns fake data from memory. No network at all.
//                   Every page works before the backend exists.
//   MOCK = false  -> api.js calls the real backend at BASE_URL.
//
// Hosted, there is always a backend, so mock mode is off. On your own machine
// there usually is not one, so it is on and every page still works.
//
// To work against a backend running locally, change this line to
//   export const MOCK = false;
// and do not commit it - that would break every teammate who has no backend
// running. The pre-commit hook described in ufh-local/README.md blocks it.
//
// Nothing else changes either way: every page calls the same functions,
// because the fake data is built to match the contract's response shapes
// field for field.
export const MOCK = isLocal;

// Which role the mock login represents. Keep student as the committed
// default; temporarily use 'responder' when exercising the responder page
// without a running backend. This has no effect when MOCK is false.
export const MOCK_ROLE = 'student';

// How long the fake responses pretend to take, in milliseconds.
// Real requests are not instant, so mock ones should not be either - this is
// what lets us see our spinners and loading states while developing.
// Raise it to 2000 if you want to stress-test a slow-connection experience.
export const MOCK_DELAY_MS = 600;

// Whether mock mode pretends to check the auth token.
//
//   false -> protected calls work with no token at all. This is what you want
//            right now: the login page does not exist yet, so a dashboard or
//            report page would be stuck behind a 401 it cannot clear.
//   true  -> protected calls throw the same 401 the real backend would.
//
// SET THIS TO TRUE once the login flow works, and leave it true.
// It is how we catch a page that forgot to check the user is signed in -
// better to find that in mock mode than after the backend goes live.
//
// Has no effect when MOCK is false. The real backend always checks the token,
// and no frontend flag can change that.
export const MOCK_ENFORCE_AUTH = false;
