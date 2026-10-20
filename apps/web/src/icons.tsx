import type { PropsWithChildren, SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement>

function Icon({ children, ...props }: PropsWithChildren<IconProps>) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{children}</svg>
}

export const ArrowLeft = (props: IconProps) => <Icon {...props}><path d="m15 18-6-6 6-6" /><path d="M9 12h11" /></Icon>
export const ArrowRight = (props: IconProps) => <Icon {...props}><path d="m9 18 6-6-6-6" /><path d="M4 12h11" /></Icon>
export const AlertTriangle = (props: IconProps) => <Icon {...props}><path d="M12 3 2 21h20L12 3Z" /><path d="M12 9v4" /><path d="M12 17h.01" /></Icon>
export const CalendarDays = (props: IconProps) => <Icon {...props}><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 11h18" /></Icon>
export const CheckCircle2 = (props: IconProps) => <Icon {...props}><circle cx="12" cy="12" r="9" /><path d="m8 12 2.5 2.5L16 9" /></Icon>
export const Circle = (props: IconProps) => <Icon {...props}><circle cx="12" cy="12" r="9" /></Icon>
export const CirclePlus = (props: IconProps) => <Icon {...props}><circle cx="12" cy="12" r="9" /><path d="M12 8v8M8 12h8" /></Icon>
export const CircleStop = (props: IconProps) => <Icon {...props}><circle cx="12" cy="12" r="9" /><rect x="9" y="9" width="6" height="6" /></Icon>
export const Clock3 = (props: IconProps) => <Icon {...props}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></Icon>
export const Cloud = (props: IconProps) => <Icon {...props}><path d="M17.5 19H6a4 4 0 0 1-.5-8 6.5 6.5 0 0 1 12.6 1.5A3.3 3.3 0 0 1 17.5 19Z" /></Icon>
export const LoaderCircle = (props: IconProps) => <Icon {...props}><path d="M21 12a9 9 0 1 1-6.2-8.6" /></Icon>
export const LogOut = (props: IconProps) => <Icon {...props}><path d="M10 17l5-5-5-5M15 12H3M21 3v18h-6" /></Icon>
export const MicOff = (props: IconProps) => <Icon {...props}><path d="m3 3 18 18M9 9v3a3 3 0 0 0 5.1 2.1M15 9V5a3 3 0 0 0-5.6-1.5M5 11v1a7 7 0 0 0 11.7 5.2M19 11v1a7 7 0 0 1-.3 2M12 19v3" /></Icon>
export const Radio = (props: IconProps) => <Icon {...props}><circle cx="12" cy="12" r="2" /><path d="M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14" /></Icon>
export const RefreshCw = (props: IconProps) => <Icon {...props}><path d="M20 7v5h-5M4 17v-5h5M6.1 9a7 7 0 0 1 11.6-2L20 9M4 15l2.3 2a7 7 0 0 0 11.6-2" /></Icon>
export const ShieldCheck = (props: IconProps) => <Icon {...props}><path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6l-8-3Z" /><path d="m9 12 2 2 4-4" /></Icon>
export const Sparkles = (props: IconProps) => <Icon {...props}><path d="m12 3 1.2 3.8L17 8l-3.8 1.2L12 13l-1.2-3.8L7 8l3.8-1.2L12 3ZM5 15l.8 2.2L8 18l-2.2.8L5 21l-.8-2.2L2 18l2.2-.8L5 15ZM19 13l.6 1.4L21 15l-1.4.6L19 17l-.6-1.4L17 15l1.4-.6L19 13Z" /></Icon>
export const Wifi = (props: IconProps) => <Icon {...props}><path d="M4 9a12 12 0 0 1 16 0M7 12a8 8 0 0 1 10 0M10 15a3 3 0 0 1 4 0M12 19h.01" /></Icon>
export const WifiOff = (props: IconProps) => <Icon {...props}><path d="m3 3 18 18M4 9a12 12 0 0 1 2.5-1.5M10.5 6.2A12 12 0 0 1 20 9M7 12a8 8 0 0 1 3-1.7M14 10.5a8 8 0 0 1 3 1.5M10 15a3 3 0 0 1 4 0M12 19h.01" /></Icon>
