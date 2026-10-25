import { useEffect, useState } from 'react'
import type { PropsWithChildren, ReactNode } from 'react'
import { ArrowLeft, LoaderCircle, LogOut } from './icons'
import { ApiError, api } from './api'
import { Brand } from './components'
import { AppLink, usePathname } from './router'
import { LivePage } from './pages/LivePage'
import { MeetingsPage } from './pages/MeetingsPage'
import { NewMeetingPage } from './pages/NewMeetingPage'
import { TopicsPage } from './pages/TopicsPage'
import { UsagePage } from './pages/UsagePage'
import { LoginPage } from './pages/LoginPage'

type AuthState = 'checking' | 'authenticated' | 'anonymous'

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
