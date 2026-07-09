// Package main is the Android entry point for grok-proxy-plus.
//
// It is built with `go build -buildmode=c-shared` to produce libgrokproxy.so,
// which is packaged under jniLibs/<abi>/ of the Android app (NOT in assets,
// because newer Android versions block loading native code from assets).
//
// This is NOT gomobile. It is full Go: the proxy, oauth, store and upstream
// packages from the original project are imported directly and driven from a
// thin cgo + JNI layer. The JNI functions are written in Go (cgo) and exported
// with //export, so the app ships a single .so with no separate C source files.
//
// The Kotlin side (com.deivid22srk.grokproxy.Bridge) declares matching
// `external fun` methods and System.loadLibrary("grokproxy").
package main

/*
#include <jni.h>
#include <stdlib.h>
#include <string.h>

// Thin inline helpers so the Go side never has to dereference the JNIEnv
// function-pointer table by hand. These live in the cgo C preamble (standard
// Go cgo practice), NOT in a separate .c file.
static jstring jni_new_string(JNIEnv *env, const char *s) {
        if (env == NULL || s == NULL) return NULL;
        return (*env)->NewStringUTF(env, s);
}

static const char *jni_get_cstr(JNIEnv *env, jstring s) {
        if (env == NULL || s == NULL) return NULL;
        return (*env)->GetStringUTFChars(env, s, NULL);
}

static void jni_release_cstr(JNIEnv *env, jstring s, const char *c) {
        if (env == NULL || s == NULL || c == NULL) return;
        (*env)->ReleaseStringUTFChars(env, s, c);
}
*/
import "C"

import (
        "context"
        "encoding/json"
        "fmt"
        "io"
        "net/http"
        "sync"
        "time"
        "unsafe"

        "grok-desktop/internal/oauth"
        "grok-desktop/internal/proxyhttp"
        "grok-desktop/internal/store"
        "grok-desktop/internal/upstream"
)

// mobileApp wires the original proxy/oauth/store/upstream together for on-device
// use. It mirrors the Wails App in ../app.go but drops the wails runtime calls
// and tracks device-login state internally so the UI can poll for completion.
type mobileApp struct {
        store    *store.Store
        oauth    *oauth.Client
        upstream *upstream.Client
        proxy    *proxyhttp.Server

        mu           sync.Mutex
        deviceCancel context.CancelFunc

        loginMu     sync.Mutex
        loginState  string // idle | pending | success | error
        loginURL    string
        loginCode   string
        loginErr    string
        loginEmail  string
        loginLabel  string
        loginExpire time.Time
}

var app = &mobileApp{loginState: "idle"}

const defaultListen = "0.0.0.0:8787"

// ---- internal helpers (pure Go, no JNI) ----

func (a *mobileApp) init(dataDir string) error {
        if a.store != nil {
                return nil // already initialized
        }
        st, err := store.Open(dataDir)
        if err != nil {
                return fmt.Errorf("store open: %w", err)
        }
        a.store = st
        a.oauth = oauth.New()
        a.upstream = upstream.New()
        a.proxy = proxyhttp.New(st, a.upstream, a.ensureCreds)
        return nil
}

// ensureCreds is the credential provider required by proxyhttp.New. It is the
// same logic as App.ensureCreds in app.go, minus the wails runtime calls.
func (a *mobileApp) ensureCreds(ctx context.Context) (string, *store.Account, store.Settings, error) {
        if a.store == nil {
                return "", nil, store.Settings{}, fmt.Errorf("store not ready")
        }
        settings := a.store.Settings()
        acc, ok := a.store.ActiveAccount()
        if !ok || acc == nil {
                return "", nil, settings, fmt.Errorf("nenhuma conta ativa — faça login primeiro")
        }
        if acc.ExpiresSoon(5*time.Minute) && acc.RefreshToken != "" {
                tok, err := a.oauth.Refresh(ctx, acc.RefreshToken, acc.ClientID, acc.Issuer)
                if err != nil {
                        if acc.Expired() {
                                return "", nil, settings, fmt.Errorf("token expirado — faça login de novo: %v", err)
                        }
                } else {
                        acc.AccessToken = tok.AccessToken
                        if tok.RefreshToken != "" {
                                acc.RefreshToken = tok.RefreshToken
                        }
                        acc.ExpiresAt = time.Now().UTC().Add(time.Duration(tok.ExpiresIn) * time.Second)
                        acc.UpdatedAt = time.Now().UTC()
                        _ = a.store.UpsertAccount(*acc)
                }
        }
        if acc.AccessToken == "" {
                return "", nil, settings, fmt.Errorf("conta sem access_token")
        }
        return acc.AccessToken, acc, settings, nil
}

func (a *mobileApp) startServer(listen string) (string, error) {
        if a.proxy == nil {
                return "", fmt.Errorf("proxy não inicializado")
        }
        if listen == "" {
                listen = defaultListen
        }
        addr := a.proxy.Addr()
        if addr != "" {
                return addr, nil // already running
        }
        if err := a.proxy.Start(listen); err != nil {
                return "", err
        }
        _ = a.store.UpdateSettings(func(s *store.Settings) {
                s.ProxyEnabled = true
                s.ProxyListen = listen
        })
        return a.proxy.Addr(), nil
}

func (a *mobileApp) stopServer() error {
        if a.proxy == nil {
                return nil
        }
        err := a.proxy.Stop(context.Background())
        _ = a.store.UpdateSettings(func(s *store.Settings) { s.ProxyEnabled = false })
        return err
}

func (a *mobileApp) startLogin() error {
        a.mu.Lock()
        if a.deviceCancel != nil {
                a.deviceCancel()
        }
        ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
        a.deviceCancel = cancel
        a.mu.Unlock()

        a.loginMu.Lock()
        a.loginState = "pending"
        a.loginErr = ""
        a.loginEmail = ""
        a.loginURL = ""
        a.loginCode = ""
        a.loginExpire = time.Now().Add(30 * time.Minute)
        a.loginMu.Unlock()

        start, err := a.oauth.StartDevice(ctx)
        if err != nil {
                cancel()
                a.setLoginError(err.Error())
                return err
        }
        url := start.VerificationURIComplete
        if url == "" {
                url = start.VerificationURI
        }
        a.loginMu.Lock()
        a.loginURL = url
        a.loginCode = start.UserCode
        if start.ExpiresIn > 0 {
                a.loginExpire = time.Now().Add(time.Duration(start.ExpiresIn) * time.Second)
        }
        a.loginMu.Unlock()

        go func() {
                tok, err := a.oauth.PollDevice(ctx, start.DeviceCode, start.Interval)
                if err != nil {
                        if ctx.Err() == nil {
                                a.setLoginError(err.Error())
                        }
                        return
                }
                acc := oauth.AccountFromToken(tok, a.oauth.ClientID, a.oauth.Issuer)
                email, uid := a.oauth.UserInfo(context.Background(), tok.AccessToken, a.oauth.Issuer)
                if email != "" {
                        acc.Email = email
                }
                if uid != "" {
                        acc.UserID = uid
                        acc.ID = uid
                }
                if prev, ok := a.store.GetAccount(acc.ID); ok && prev != nil {
                        if prev.Label != "" && prev.Label != prev.Email && prev.Label != "Grok account" {
                                acc.Label = prev.Label
                        }
                        acc.CreatedAt = prev.CreatedAt
                }
                if acc.Label == "" || acc.Label == "Grok account" {
                        if acc.Email != "" {
                                acc.Label = acc.Email
                        } else if len(acc.ID) >= 8 {
                                acc.Label = "Conta " + acc.ID[:8]
                        } else {
                                acc.Label = "Conta"
                        }
                }
                if err := a.store.UpsertAccount(acc); err != nil {
                        a.setLoginError(err.Error())
                        return
                }
                _ = a.store.SetActiveAccount(acc.ID)
                a.loginMu.Lock()
                a.loginState = "success"
                a.loginEmail = acc.Email
                a.loginLabel = acc.Label
                a.loginURL = ""
                a.loginCode = ""
                a.loginErr = ""
                a.loginMu.Unlock()
        }()

        return nil
}

func (a *mobileApp) setLoginError(msg string) {
        a.loginMu.Lock()
        defer a.loginMu.Unlock()
        a.loginState = "error"
        a.loginErr = msg
}

func (a *mobileApp) cancelLogin() {
        a.mu.Lock()
        if a.deviceCancel != nil {
                a.deviceCancel()
                a.deviceCancel = nil
        }
        a.mu.Unlock()
        a.loginMu.Lock()
        a.loginState = "idle"
        a.loginURL = ""
        a.loginCode = ""
        a.loginErr = ""
        a.loginMu.Unlock()
}

// ---- Usage / account info ----

// usageResp is the JSON envelope returned by nativeGetUsage. It combines:
//   - rate_limits: live quota from X-Ratelimit-* headers captured by the proxy
//   - billing: paid-plan usage from GET /v1/billing (cli-chat-proxy.grok.com)
//   - user: account profile from GET /v1/user
//   - account_blocked: derived from the /v1/me team_blocked flag
type usageResp struct {
        RateLimits     proxyhttp.RateLimit `json:"rate_limits"`
        Billing        *billingConfig      `json:"billing,omitempty"`
        User           *userInfo           `json:"user,omitempty"`
        AccountBlocked bool                `json:"account_blocked"`
        Email          string              `json:"email"`
        Error          string              `json:"error,omitempty"`
}

// billingConfig mirrors the relevant fields of GET /v1/billing.
type billingConfig struct {
        Used             jsonVal `json:"used"`
        MonthlyLimit     jsonVal `json:"monthly_limit"`
        OnDemandCap      jsonVal `json:"on_demand_cap"`
        PeriodStart      string  `json:"billing_period_start"`
        PeriodEnd        string  `json:"billing_period_end"`
        History          []billingHistory `json:"history,omitempty"`
}

type billingHistory struct {
        Year         int     `json:"year"`
        Month        int     `json:"month"`
        IncludedUsed jsonVal `json:"included_used"`
        OnDemandUsed jsonVal `json:"on_demand_used"`
        TotalUsed    jsonVal `json:"total_used"`
}

// userInfo mirrors the relevant fields of GET /v1/user.
type userInfo struct {
        Email            string `json:"email"`
        FirstName        string `json:"first_name"`
        HasGrokCodeAccess bool  `json:"has_grok_code_access"`
        UserID           string `json:"user_id"`
}

// jsonVal unwraps the xAI {"val": N} wrapper into a plain number.
type jsonVal int64

func (v *jsonVal) UnmarshalJSON(b []byte) error {
        var raw map[string]any
        if err := json.Unmarshal(b, &raw); err != nil {
                return err
        }
        if val, ok := raw["val"]; ok {
                switch n := val.(type) {
                case float64:
                        *v = jsonVal(int64(n))
                case json.Number:
                        n64, _ := n.Int64()
                        *v = jsonVal(n64)
                }
        }
        return nil
}

// getUsage assembles the usage snapshot. The rate-limits are read from the
// proxy (instant, from the last proxied request); billing + user are fetched
// from the xAI API using the active account's access token. If no account is
// logged in, only the rate-limit section is returned (with HasData=false).
func (a *mobileApp) getUsage() usageResp {
        resp := usageResp{}
        if a.proxy != nil {
                resp.RateLimits = a.proxy.RateLimit()
        }

        token, acc, _, err := a.ensureCreds(context.Background())
        if err != nil {
                resp.Error = err.Error()
                return resp
        }
        resp.Email = acc.Email

        // Fetch /v1/billing, /v1/user and /v1/me in parallel — they're
        // independent and each takes ~200ms.
        var (
                wg      sync.WaitGroup
                billing *billingConfig
                user    *userInfo
                blocked bool
        )
        const upstreamBase = "https://cli-chat-proxy.grok.com/v1"
        const apiBase = "https://api.x.ai/v1"

        wg.Add(3)
        go func() {
                defer wg.Done()
                billing = fetchJSON(upstreamBase+"/billing", token, parseBilling)
        }()
        go func() {
                defer wg.Done()
                user = fetchJSON(upstreamBase+"/user", token, parseUser)
        }()
        go func() {
                defer wg.Done()
                blocked = fetchMeBlocked(apiBase+"/me", token)
        }()
        wg.Wait()

        resp.Billing = billing
        resp.User = user
        resp.AccountBlocked = blocked
        return resp
}

// fetchJSON is a small generic helper: GET url with the bearer token, decode
// the JSON body through decodeFn. Returns nil on any error (the usage card
// just omits the section).
func fetchJSON[T any](url, token string, decodeFn func([]byte) (T, error)) T {
        var zero T
        req, err := http.NewRequest("GET", url, nil)
        if err != nil {
                return zero
        }
        req.Header.Set("Authorization", "Bearer "+token)
        req.Header.Set("Accept", "application/json")
        req.Header.Set("x-grok-client-version", store.DefaultClientVersion)
        req.Header.Set("x-grok-client-surface", "grok-desktop")
        client := &http.Client{Timeout: 15 * time.Second}
        resp, err := client.Do(req)
        if err != nil || resp.StatusCode >= 400 {
                if resp != nil {
                        resp.Body.Close()
                }
                return zero
        }
        body, _ := io.ReadAll(resp.Body)
        resp.Body.Close()
        result, err := decodeFn(body)
        if err != nil {
                return zero
        }
        return result
}

func parseBilling(b []byte) (*billingConfig, error) {
        // The xAI /v1/billing response nests everything under "config".
        var raw struct {
                Config struct {
                        Used             jsonVal           `json:"used"`
                        MonthlyLimit     jsonVal           `json:"monthlyLimit"`
                        OnDemandCap      jsonVal           `json:"onDemandCap"`
                        BillingPeriodStart string          `json:"billingPeriodStart"`
                        BillingPeriodEnd   string          `json:"billingPeriodEnd"`
                        History          []struct {
                                BillingCycle struct {
                                        Year  int `json:"year"`
                                        Month int `json:"month"`
                                } `json:"billingCycle"`
                                IncludedUsed jsonVal `json:"includedUsed"`
                                OnDemandUsed jsonVal `json:"onDemandUsed"`
                                TotalUsed    jsonVal `json:"totalUsed"`
                        } `json:"history"`
                } `json:"config"`
        }
        if err := json.Unmarshal(b, &raw); err != nil {
                return nil, err
        }
        c := &billingConfig{
                Used:         raw.Config.Used,
                MonthlyLimit: raw.Config.MonthlyLimit,
                OnDemandCap:  raw.Config.OnDemandCap,
                PeriodStart:  raw.Config.BillingPeriodStart,
                PeriodEnd:    raw.Config.BillingPeriodEnd,
        }
        for _, h := range raw.Config.History {
                c.History = append(c.History, billingHistory{
                        Year:         h.BillingCycle.Year,
                        Month:        h.BillingCycle.Month,
                        IncludedUsed: h.IncludedUsed,
                        OnDemandUsed: h.OnDemandUsed,
                        TotalUsed:    h.TotalUsed,
                })
        }
        return c, nil
}

func parseUser(b []byte) (*userInfo, error) {
        var raw struct {
                Email             string `json:"email"`
                FirstName         string `json:"firstName"`
                HasGrokCodeAccess bool   `json:"hasGrokCodeAccess"`
                UserID            string `json:"userId"`
        }
        if err := json.Unmarshal(b, &raw); err != nil {
                return nil, err
        }
        return &userInfo{
                Email:             raw.Email,
                FirstName:         raw.FirstName,
                HasGrokCodeAccess: raw.HasGrokCodeAccess,
                UserID:            raw.UserID,
        }, nil
}

// fetchMeBlocked returns true if GET /v1/me reports team_blocked=true.
func fetchMeBlocked(url, token string) bool {
        req, _ := http.NewRequest("GET", url, nil)
        req.Header.Set("Authorization", "Bearer "+token)
        req.Header.Set("Accept", "application/json")
        req.Header.Set("x-grok-client-version", store.DefaultClientVersion)
        client := &http.Client{Timeout: 15 * time.Second}
        resp, err := client.Do(req)
        if err != nil {
                return false
        }
        defer resp.Body.Close()
        body, _ := io.ReadAll(resp.Body)
        var raw struct {
                TeamBlocked bool `json:"team_blocked"`
        }
        _ = json.Unmarshal(body, &raw)
        return raw.TeamBlocked
}

// ---- JSON responses ----

type statusResp struct {
        Running     bool              `json:"running"`
        Addr        string            `json:"addr"`
        BaseURL     string            `json:"base_url"`
        APIKey      string            `json:"api_key"`
        Model       string            `json:"model"`
        ActiveEmail string            `json:"active_email"`
        ActiveLabel string            `json:"active_label"`
        Accounts    []map[string]any  `json:"accounts"`
        LoginState  string            `json:"login_state"`
        LoginURL    string            `json:"login_url"`
        LoginCode   string            `json:"login_code"`
        LoginEmail  string            `json:"login_email"`
        LoginError  string            `json:"login_error"`
        DataDir     string            `json:"data_dir"`
}

func (a *mobileApp) status() statusResp {
        r := statusResp{LoginState: "idle"}
        if a.store != nil {
                r.DataDir = a.store.Root()
                r.APIKey = a.store.Settings().ProxyAPIKey
                r.Model = a.store.Settings().DefaultModel
                if r.Model == "" {
                        r.Model = store.DefaultModel
                }
                if r.APIKey == "" {
                        r.APIKey = "grok-mobile"
                }
                r.Accounts = a.store.PublicAccounts()
                if acc, ok := a.store.ActiveAccount(); ok && acc != nil {
                        r.ActiveEmail = acc.Email
                        r.ActiveLabel = acc.Label
                }
        }
        if a.proxy != nil {
                r.Addr = a.proxy.Addr()
        }
        r.Running = r.Addr != ""
        if r.Addr != "" {
                // On-device clients reach the proxy through 127.0.0.1 even when bound
                // to 0.0.0.0, so normalise for display.
                display := r.Addr
                if len(display) >= 7 && display[:7] == "0.0.0.0" {
                        display = "127.0.0.1" + display[7:]
                }
                r.BaseURL = "http://" + display + "/v1"
        }
        a.loginMu.Lock()
        r.LoginState = a.loginState
        r.LoginURL = a.loginURL
        r.LoginCode = a.loginCode
        r.LoginEmail = a.loginEmail
        r.LoginError = a.loginErr
        a.loginMu.Unlock()
        if r.LoginState == "" {
                r.LoginState = "idle"
        }
        return r
}

// ---- JNI <-> Go marshalling helpers ----

// jstr converts a Go string to a jstring. The Go string is first copied to a C
// buffer (freed immediately) and handed to NewStringUTF, which makes its own
// managed copy on the JVM side.
func jstr(env *C.JNIEnv, s string) C.jstring {
        cs := C.CString(s)
        defer C.free(unsafe.Pointer(cs))
        return C.jni_new_string(env, cs)
}

// gostr reads a jstring back into a Go string.
func gostr(env *C.JNIEnv, s C.jstring) string {
        if s == 0 {
                return ""
        }
        cs := C.jni_get_cstr(env, s)
        if cs == nil {
                return ""
        }
        defer C.jni_release_cstr(env, s, cs)
        return C.GoString(cs)
}

// jsonOut marshals v and returns it as a jstring. On marshal error it returns a
// tiny JSON error object so the Kotlin side always gets valid JSON.
func jsonOut(env *C.JNIEnv, v any) C.jstring {
        b, err := json.Marshal(v)
        if err != nil {
                return jstr(env, `{"error":"marshal: `+err.Error()+`"}`)
        }
        return jstr(env, string(b))
}

// errOut returns {"error": msg} as a jstring, or {"ok":true} when msg is empty.
func errOut(env *C.JNIEnv, msg string) C.jstring {
        if msg == "" {
                return jstr(env, `{"ok":true}`)
        }
        return jstr(env, `{"error":`+jsonString(msg)+`}`)
}

func jsonString(s string) string {
        b, _ := json.Marshal(s)
        return string(b)
}

// ---- Exported JNI functions ----
//
// Each maps 1:1 to an `external fun` in com.deivid22srk.grokproxy.Bridge.
// The naming follows the JNI convention:
//
//      Java_com_deivid22srk_grokproxy_Bridge_<method>

//export Java_com_deivid22srk_grokproxy_Bridge_nativeInit
func Java_com_deivid22srk_grokproxy_Bridge_nativeInit(env *C.JNIEnv, thiz C.jobject, dataDir C.jstring) C.jstring {
        dir := gostr(env, dataDir)
        if err := app.init(dir); err != nil {
                return errOut(env, err.Error())
        }
        return errOut(env, "")
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeStartServer
func Java_com_deivid22srk_grokproxy_Bridge_nativeStartServer(env *C.JNIEnv, thiz C.jobject, listen C.jstring) C.jstring {
        l := gostr(env, listen)
        addr, err := app.startServer(l)
        if err != nil {
                return errOut(env, err.Error())
        }
        return jstr(env, `{"ok":true,"addr":`+jsonString(addr)+`}`)
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeStopServer
func Java_com_deivid22srk_grokproxy_Bridge_nativeStopServer(env *C.JNIEnv, thiz C.jobject) C.jstring {
        if err := app.stopServer(); err != nil {
                return errOut(env, err.Error())
        }
        return errOut(env, "")
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeStatus
func Java_com_deivid22srk_grokproxy_Bridge_nativeStatus(env *C.JNIEnv, thiz C.jobject) C.jstring {
        return jsonOut(env, app.status())
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeStartLogin
func Java_com_deivid22srk_grokproxy_Bridge_nativeStartLogin(env *C.JNIEnv, thiz C.jobject) C.jstring {
        if err := app.startLogin(); err != nil {
                return errOut(env, err.Error())
        }
        // Return the immediate state (URL + code) so the UI can render without a
        // second round-trip.
        return jsonOut(env, app.status())
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeLoginStatus
func Java_com_deivid22srk_grokproxy_Bridge_nativeLoginStatus(env *C.JNIEnv, thiz C.jobject) C.jstring {
        return jsonOut(env, app.status())
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeCancelLogin
func Java_com_deivid22srk_grokproxy_Bridge_nativeCancelLogin(env *C.JNIEnv, thiz C.jobject) C.jstring {
        app.cancelLogin()
        return errOut(env, "")
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeListAccounts
func Java_com_deivid22srk_grokproxy_Bridge_nativeListAccounts(env *C.JNIEnv, thiz C.jobject) C.jstring {
        if app.store == nil {
                return errOut(env, "store not ready")
        }
        return jsonOut(env, map[string]any{"accounts": app.store.PublicAccounts()})
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeLogout
func Java_com_deivid22srk_grokproxy_Bridge_nativeLogout(env *C.JNIEnv, thiz C.jobject, id C.jstring) C.jstring {
        if app.store == nil {
                return errOut(env, "store not ready")
        }
        if err := app.store.RemoveAccount(gostr(env, id)); err != nil {
                return errOut(env, err.Error())
        }
        return errOut(env, "")
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeSetActive
func Java_com_deivid22srk_grokproxy_Bridge_nativeSetActive(env *C.JNIEnv, thiz C.jobject, id C.jstring) C.jstring {
        if app.store == nil {
                return errOut(env, "store not ready")
        }
        if err := app.store.SetActiveAccount(gostr(env, id)); err != nil {
                return errOut(env, err.Error())
        }
        return errOut(env, "")
}

//export Java_com_deivid22srk_grokproxy_Bridge_nativeGetUsage
func Java_com_deivid22srk_grokproxy_Bridge_nativeGetUsage(env *C.JNIEnv, thiz C.jobject) C.jstring {
        return jsonOut(env, app.getUsage())
}

// main is required for -buildmode=c-shared. It never runs on Android (the JVM
// drives entry through JNI), but must exist for the linker.
func main() {}
