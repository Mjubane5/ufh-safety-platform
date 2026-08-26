// Frontend configuration.
//
// This is the only file you edit when you move between working on your own
// machine, working against a teammate's backend, or demoing the finished app.
// Nothing else in the frontend should hardcode a URL.

// Where the Spring Boot backend lives. Matches docs/api-contract.md section 1.
// No trailing slash - api.js adds the path itself.
export const BASE_URL = 'http://localhost:8080/api';

// The MOCK switch.
//
//   MOCK = true   -> api.js returns fake data from memory. No network at all.
//                   Every page works before the backend exists.
//   MOCK = false  -> api.js calls the real backend at BASE_URL.
//
// Flip this ONE line to false once the backend endpoints are running.
// Nothing else changes: every page calls the same functions either way,
// because the fake data is built to match the contract's response shapes
// field for field.
export const MOCK = true;

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
