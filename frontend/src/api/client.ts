import axios from 'axios'

/**
 * Relative base URL, deliberately not configurable (finding C13, ADR-011):
 * one build artefact runs in every environment, and the refresh cookie's
 * SameSite=Strict only works because the SPA and API share an origin.
 */
export const api = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  timeout: 10_000,
})
