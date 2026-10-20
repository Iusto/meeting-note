/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: '#17201e',
        moss: '#23594d',
        mint: '#dff2e9',
        cream: '#f7f5ef',
        coral: '#e46f51',
      },
      boxShadow: {
        soft: '0 20px 60px rgba(30, 50, 44, 0.10)',
      },
    },
  },
  plugins: [],
}
