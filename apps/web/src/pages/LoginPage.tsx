import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, api } from '../api'
import { Brand } from '../components'
import { LoaderCircle } from '../icons'

export function LoginPage({ onAuthenticated }: { onAuthenticated: () => void }) {
  const [setupRequired, setSetupRequired] = useState<boolean | null>(null)
  const [token, setToken] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [rememberMe, setRememberMe] = useState(true)
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function loadConfiguration() {
    setError('')
    try { setSetupRequired((await api.getLoginConfiguration()).setupRequired) }
    catch { setError('서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.') }
  }

  useEffect(() => { void loadConfiguration() }, [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (setupRequired === null || submitting) return
    if (setupRequired && password !== confirmation) {
      setError('비밀번호가 일치하지 않습니다.')
      return
    }
    setSubmitting(true)
    setError('')
    try {
      if (setupRequired) await api.setupOwner({ token, username, password, rememberMe })
      else await api.login({ username, password, rememberMe })
      setToken(''); setPassword(''); setConfirmation('')
      onAuthenticated()
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) {
        setSetupRequired(false); setToken(''); setPassword(''); setConfirmation('')
        setError('이미 계정이 등록되어 있습니다. 아이디와 비밀번호로 로그인해 주세요.')
      } else if (caught instanceof ApiError && caught.status === 401) {
        setError(setupRequired ? '접속 토큰을 확인해 주세요.' : '아이디 또는 비밀번호를 확인해 주세요.')
      } else if (caught instanceof ApiError && caught.status === 429) {
        setError('시도 횟수가 많습니다. 잠시 후 다시 시도해 주세요.')
      } else setError('요청을 처리하지 못했습니다. 입력 내용과 서버 연결을 확인해 주세요.')
    } finally { setSubmitting(false) }
  }

  return (
    <main className="grid min-h-screen place-items-center bg-cream px-5 py-20">
      <div className="absolute left-8 top-8"><Brand /></div>
      <section className="w-full max-w-md rounded-[2rem] border border-white bg-white/85 p-8 shadow-soft backdrop-blur">
        <p className="eyebrow">개인 작업 공간</p>
        <h1 className="mt-3 text-3xl font-black tracking-[-0.04em] text-ink">{setupRequired ? '내 계정 만들기' : '내 회의록에 접속'}</h1>
        <p className="mt-3 text-sm leading-6 text-slate-500">{setupRequired
          ? '최초 한 번만 접속 토큰으로 소유자를 확인합니다. 이후에는 아이디와 비밀번호로 로그인하세요.'
          : '아이디와 비밀번호로 내 회의록을 이어서 확인하세요.'}</p>
        {setupRequired === null ? (
          <div className="mt-7">
            {error ? <><p role="alert" className="text-sm text-red-700">{error}</p><button type="button" className="button-secondary mt-4" onClick={() => void loadConfiguration()}>다시 연결</button></>
              : <LoaderCircle aria-label="로그인 설정 확인 중" className="h-6 w-6 animate-spin text-moss" />}
          </div>
        ) : (
          <form className="mt-7 space-y-4" onSubmit={submit}>
            {setupRequired && <div><label className="block text-sm font-bold text-ink" htmlFor="owner-token">접속 토큰</label>
              <input id="owner-token" type="password" autoComplete="off" className="field mt-2" value={token} onChange={event => setToken(event.target.value)} required maxLength={512} /></div>}
            <div><label className="block text-sm font-bold text-ink" htmlFor="username">아이디</label>
              <input id="username" autoComplete="username" autoCapitalize="none" spellCheck={false} className="field mt-2" value={username} onChange={event => setUsername(event.target.value)} required minLength={3} maxLength={64} pattern="[A-Za-z0-9_.\-]{3,64}" aria-describedby={setupRequired ? 'username-help' : undefined} />
              {setupRequired && <p id="username-help" className="mt-1 text-xs text-slate-500">영문·숫자·마침표·밑줄·하이픈 3~64자</p>}</div>
            <div><label className="block text-sm font-bold text-ink" htmlFor="password">비밀번호</label>
              <input id="password" type="password" autoComplete={setupRequired ? 'new-password' : 'current-password'} className="field mt-2" value={password} onChange={event => setPassword(event.target.value)} required minLength={setupRequired ? 12 : undefined} maxLength={128} aria-describedby={setupRequired ? 'password-help' : undefined} />
              {setupRequired && <p id="password-help" className="mt-1 text-xs text-slate-500">12~128자. 기억하기 쉬운 긴 문장도 사용할 수 있습니다.</p>}</div>
            {setupRequired && <div><label className="block text-sm font-bold text-ink" htmlFor="password-confirmation">비밀번호 확인</label>
              <input id="password-confirmation" type="password" autoComplete="new-password" className="field mt-2" value={confirmation} onChange={event => setConfirmation(event.target.value)} required maxLength={128} /></div>}
            <label className="flex items-center gap-2 text-sm text-ink"><input type="checkbox" checked={rememberMe} onChange={event => setRememberMe(event.target.checked)} />이 기기에서 30일 동안 로그인 유지</label>
            <p className="text-xs text-slate-500">공용 기기에서는 로그인 유지를 해제하세요.</p>
            {error && <p role="alert" className="text-sm font-semibold text-red-700">{error}</p>}
            <button className="button-primary w-full" disabled={submitting}>
              {submitting && <LoaderCircle className="h-4 w-4 animate-spin" />}{setupRequired ? '계정 만들고 시작하기' : '로그인'}
            </button>
          </form>
        )}
      </section>
    </main>
  )
}
