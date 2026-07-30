// TASK: P1-T10
'use client'

import { useRef, type ClipboardEvent, type KeyboardEvent } from 'react'

const OTP_CHAR_RE = /[^A-Z2-9]/g
export const OTP_LENGTH = 6

interface OtpInputProps {
  value: string[]
  onChange: (val: string[]) => void
}

/** 6-cell OTP entry with auto-advance, backspace navigation and paste support. */
export function OtpInput({ value, onChange }: OtpInputProps) {
  const refs = useRef<Array<HTMLInputElement | null>>([])

  const handleChange = (idx: number, raw: string) => {
    const char = raw.toUpperCase().replace(OTP_CHAR_RE, '').slice(-1)
    const next = [...value]
    next[idx] = char
    onChange(next)
    if (char && idx < OTP_LENGTH - 1) {
      refs.current[idx + 1]?.focus()
    }
  }

  const handleKeyDown = (idx: number, e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !value[idx] && idx > 0) {
      refs.current[idx - 1]?.focus()
    }
  }

  const handlePaste = (e: ClipboardEvent<HTMLDivElement>) => {
    e.preventDefault()
    const text = e.clipboardData
      .getData('text')
      .toUpperCase()
      .replace(OTP_CHAR_RE, '')
    const next = Array<string>(OTP_LENGTH).fill('')
    text
      .split('')
      .slice(0, OTP_LENGTH)
      .forEach((c, i) => {
        next[i] = c
      })
    onChange(next)
    refs.current[Math.min(text.length, OTP_LENGTH - 1)]?.focus()
  }

  return (
    <div className="flex gap-2" onPaste={handlePaste}>
      {Array.from({ length: OTP_LENGTH }, (_, i) => (
        <input
          key={i}
          ref={(el) => {
            refs.current[i] = el
          }}
          inputMode="text"
          aria-label={`Code character ${i + 1}`}
          className="h-14 w-12 rounded-lg border border-gray-300 text-center text-xl uppercase"
          maxLength={1}
          value={value[i] ?? ''}
          onChange={(e) => handleChange(i, e.target.value)}
          onKeyDown={(e) => handleKeyDown(i, e)}
        />
      ))}
    </div>
  )
}
