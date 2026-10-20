import { useEffect, useState } from 'react'
import type { FormEvent, PropsWithChildren, ReactNode } from 'react'
import { ArrowLeft, LoaderCircle, LogOut } from './icons'
import { ApiError, api } from './api'
import { Brand } from './components'
import { AppLink, usePathname } from './router'
import { LivePage } from './pages/LivePage'
import { MeetingsPage } from './pages/MeetingsPage'
import { NewMeetingPage } from './pages/NewMeetingPage'
import { TopicsPage } from './pages/TopicsPage'
import { UsagePage } from './pages/UsagePage'

type AuthState = 'checking' | 'authenticated' | 'anonymous'

function LoginPage({ onAuthenticated }: { onAuthenticated: () => void }) {
  const [token, setToken] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!token.trim()) return
    setSubmitting(true)
    setError('')
    try {
      await api.createSession(token)
      setToken('')
      onAuthenticated()
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '접속 토큰을 확인해 주세요.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="grid min-h-screen place-items-center bg-cream px-5 py-10">
      <div className="absolute left-8 top-8"><Brand /></div>
      <section className="w-full max-w-md rounded-[2rem] border border-white bg-white/85 p-8 shadow-soft backdrop-blur">
        <p className="eyebrow">개인 작업 공간</p>
        <h1 className="mt-3 text-3xl font-black tracking-[-0.04em] text-ink">내 회의록에 접속</h1>
        <p className="mt-3 text-sm leading-6 text-slate-500">서버에서 발급한 접속 토큰을 입력하세요. 토큰은 브라우저에 저장하지 않고, 인증 후 HttpOnly 세션 쿠키만 사용합니다.</p>
        <form className="mt-7 space-y-4" onSubmit={submit}>
          <label className="block text-sm font-bold text-ink" htmlFor="token">접속 토큰</label>
          <input id="token" type="password" autoComplete="current-password" className="field" value={token} onChange={(event) => setToken(event.target.value)} placeholder="••••••••••••" />
          {error && <p role="alert" className="text-sm font-semibold text-red-700">{error}</p>}
          <button className="button-primary w-full" disabled={submitting || !token.trim()}>
            {submitting && <LoaderCircle className="h-4 w-4 animate-spin" />}
            접속하기
          </button>
        </form>
      </section>
    </main>
  )
}

function Shell({ children, onLogout }: PropsWithChildren<{ onLogout: () => Promise<void> }>) {
  return (
    <div className="min-h-screen bg-cream text-ink">
      <header className="border-b border-moss/10 bg-cream/90 px-5 py-4 backdrop-blur md:px-10">
        <div className="mx-auto flex max-w-7xl items-center justify-between"><Brand /><div className="flex items-center gap-2"><span className="rounded-full bg-mint px-3 py-1.5 text-xs font-black text-moss">AI MOCK MODE</span><button type="button" onClick={() => void onLogout()} className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-2 text-xs font-black text-moss hover:bg-mint focus:outline-none focus-visible:ring-2 focus-visible:ring-moss"><LogOut className="h-4 w-4" />로그아웃</button></div></div>
        <nav className="mx-auto mt-3 flex max-w-7xl gap-5 text-xs font-bold text-moss" aria-label="주 메뉴"><AppLink href="/meetings">회의</AppLink><AppLink href="/topics">주제·용어 사전</AppLink><AppLink href="/usage">사용량·예산</AppLink></nav>
      </header>
      {children}
    </div>
  )
}

export default function App() {
  const pathname = usePathname()
  const [auth, setAuth] = useState<AuthState>('checking')

  useEffect(() => {
    api.getSession().then(() => setAuth('authenticated')).catch((error) => {
      setAuth(error instanceof ApiError && error.status === 401 ? 'anonymous' : 'authenticated')
    })
  }, [])

  if (auth === 'checking') return <main className="grid min-h-screen place-items-center bg-cream"><LoaderCircle aria-label="불러오는 중" className="h-8 w-8 animate-spin text-moss" /></main>
  if (auth === 'anonymous') return <LoginPage onAuthenticated={() => setAuth('authenticated')} />

  const liveMatch = pathname.match(/^\/meetings\/([^/]+)\/live$/)
  let page: ReactNode
  if (pathname === '/meetings' || pathname === '/') page = <MeetingsPage onUnauthorized={() => setAuth('anonymous')} />
  else if (pathname === '/meetings/new') page = <NewMeetingPage onUnauthorized={() => setAuth('anonymous')} />
  else if (pathname === '/topics') page = <TopicsPage onUnauthorized={() => setAuth('anonymous')} />
  else if (pathname === '/usage') page = <UsagePage onUnauthorized={() => setAuth('anonymous')} />
  else if (liveMatch) page = <LivePage key={liveMatch[1]} meetingId={decodeURIComponent(liveMatch[1])} onUnauthorized={() => setAuth('anonymous')} />
  else page = (
    <main className="mx-auto max-w-xl px-5 py-24 text-center">
      <p className="text-7xl font-black text-moss/20">404</p><h1 className="mt-3 text-2xl font-black">페이지를 찾을 수 없어요</h1>
      <AppLink className="button-secondary mt-6 inline-flex" href="/meetings"><ArrowLeft className="h-4 w-4" />회의 목록으로</AppLink>
    </main>
  )
  async function logout() {
    try { await api.deleteSession() } finally { setAuth('anonymous') }
  }

  return <Shell onLogout={logout}>{page}</Shell>
}
