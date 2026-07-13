// Regressão do crash "y.filter is not a function" na Visão Geral da plataforma:
// GET /admin/tenants/migration-status devolve um OBJETO agregado
// ({ latestAvailableVersion, tenantsWithDrift, tenants: [...] }), NÃO um array.
// A página deve derivar o KPI "Aguardando migrations" de tenantsWithDrift sem
// crashar, e degradar graciosamente se o contrato vier fora do esperado.

import { render, screen, waitFor } from '@testing-library/react'
import PlataformaPage from '../page'

// Mock por caminho relativo (resolve para o mesmo arquivo que a página importa via
// alias '@/...'; o alias não resolve sob jest.mock neste projeto). A página importa
// de '@/lib/use-super-admin-guard', que só reexporta o hook de '@/hooks/...'.
jest.mock('../../../hooks/useSuperAdminGuard', () => ({
  useSuperAdminGuard: () => ({ loading: false }),
}))

const get = jest.fn()
jest.mock('../../../lib/api', () => ({
  api: { get: (...args: unknown[]) => get(...args) },
  ApiError: class ApiError extends Error {},
}))

const TENANTS = [
  { id: '1', slug: 'burg-a', displayName: 'Burg A', plan: 'PRO', isActive: true, expiresAt: null },
  { id: '2', slug: 'burg-b', displayName: 'Burg B', plan: 'BASIC', isActive: false, expiresAt: null },
]

function mockApi(migrationResult: unknown) {
  get.mockImplementation((url: string) => {
    if (url === '/admin/tenants') return Promise.resolve(TENANTS)
    if (url === '/admin/tenants/migration-status') return Promise.resolve(migrationResult)
    return Promise.reject(new Error(`unexpected url ${url}`))
  })
}

afterEach(() => get.mockReset())

test('usa tenantsWithDrift do objeto agregado sem crashar', async () => {
  mockApi({
    latestAvailableVersion: '63',
    tenantsWithDrift: 2,
    tenants: [
      { tenantSlug: 'burg-a', appliedVersion: '61', latestVersion: '63', drift: true, lastAppliedAt: null, lastSuccess: true },
      { tenantSlug: 'burg-b', appliedVersion: null, latestVersion: '63', drift: true, lastAppliedAt: null, lastSuccess: true },
    ],
  })

  render(<PlataformaPage />)

  expect(await screen.findByText('Aguardando migrations')).toBeInTheDocument()
  // KPI reflete o agregado do backend (2), não um filter sobre array inexistente.
  const kpi = screen.getByText('Aguardando migrations').closest('div')?.parentElement
  expect(kpi).toHaveTextContent('2')
  // Total de empresas continua funcionando (contrato de /admin/tenants é array).
  expect(screen.getByText('Total de empresas').closest('div')?.parentElement).toHaveTextContent('2')
})

test('degrada para 0 se o contrato de migration vier inesperado (array legado)', async () => {
  // Simula um contrato antigo/errado: array em vez de objeto. Não pode crashar.
  mockApi([{ foo: 'bar' }])

  render(<PlataformaPage />)

  await waitFor(() =>
    expect(screen.getByText('Aguardando migrations').closest('div')?.parentElement).toHaveTextContent('0'),
  )
})
