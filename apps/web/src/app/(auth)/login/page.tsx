// TASK: P1-T10
import { IdentifierInput } from '@/components/auth/IdentifierInput'

export default function LoginPage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6">
      <h1 className="text-2xl font-semibold">Sign in</h1>
      <p className="mt-2 text-sm text-gray-600">
        Enter your email address or phone number and we will send you a
        one-time code.
      </p>
      <div className="mt-8">
        <IdentifierInput />
      </div>
    </main>
  )
}
